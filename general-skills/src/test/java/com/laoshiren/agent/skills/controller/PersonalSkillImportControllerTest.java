package com.laoshiren.agent.skills.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.mapper.PersonalSkillMapper;
import com.laoshiren.agent.skills.parser.SkillMarkdownParser;
import com.laoshiren.agent.skills.service.SkillImportService;
import com.laoshiren.agent.skills.service.impl.PersonalSkillServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP、解析、业务校验与创建逻辑贯通，仅隔离数据库访问。 */
class PersonalSkillImportControllerTest {
    private static final String URL = "/api/personal-skills/import";
    private static final String MARKDOWN = "---\nname: imported-review\ndescription: 检查代码\n---\n# 审查\n";
    private static ValidatorFactory validators;
    private PersonalSkillMapper mapper;
    private SkillImportService imports;
    private MockMvc http;

    @BeforeAll
    static void configure() {
        validators = Validation.buildDefaultValidatorFactory();
        new MybatisConfiguration().addMapper(PersonalSkillMapper.class);
    }

    @AfterAll
    static void closeValidators() {
        validators.close();
    }

    @BeforeEach
    void setUp() {
        mapper = mock(PersonalSkillMapper.class);
        imports = new SkillImportService(new SkillMarkdownParser(),
                new PersonalSkillServiceImpl(mapper, validators.getValidator()));
        http = MockMvcBuilders.standaloneSetup(new PersonalSkillImportController(imports))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
    }

    @Test
    void importsDraftWithHeaderOwnershipAndIgnoresLifecycleFieldsFromFile() throws Exception {
        var persisted = new PersonalSkill();
        when(mapper.insert(any(PersonalSkill.class))).thenAnswer(invocation -> {
            PersonalSkill row = invocation.getArgument(0);
            assertThat(row.getId()).isNull();
            assertThat(row.getCreatedAt()).isNull();
            assertThat(row.getUpdatedAt()).isNull();
            row.setId(123L);
            org.springframework.beans.BeanUtils.copyProperties(row, persisted);
            persisted.setCreatedAt(OffsetDateTime.parse("2026-11-18T08:00:00Z"));
            persisted.setUpdatedAt(persisted.getCreatedAt());
            return 1;
        });
        when(mapper.selectOne(any())).thenReturn(persisted);

        http.perform(upload("""
                        ---
                        name: imported-review
                        description: 检查代码
                        tags: [java, spring]
                        tenantId: 20
                        ownerUserId: 200
                        id: 999
                        status: ENABLED
                        revision: 99
                        createdAt: 2000-01-01
                        updatedAt: 2000-01-01
                        ---
                        # 审查
                        """).param("tenantId", "20").param("ownerUserId", "200"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/personal-skills/123"))
                .andExpect(jsonPath("$.id").value(123))
                .andExpect(jsonPath("$.tenantId").value(10))
                .andExpect(jsonPath("$.ownerUserId").value(100))
                .andExpect(jsonPath("$.name").value("imported-review"))
                .andExpect(jsonPath("$.description").value("检查代码"))
                .andExpect(jsonPath("$.instructionsMd").value("# 审查\n"))
                .andExpect(jsonPath("$.extraFrontmatter.tags[0]").value("java"))
                .andExpect(jsonPath("$.extraFrontmatter.name").doesNotExist())
                .andExpect(jsonPath("$.extraFrontmatter.description").doesNotExist())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.createdAt").value("2026-11-18T08:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-18T08:00:00Z"));
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void reportsMissingContentAndYamlErrorsWithoutWritingToDatabase(String markdown, String reason) throws Exception {
        http.perform(upload(markdown))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", containsString(reason)));
        verifyNoInteractions(mapper);
    }

    static Stream<Arguments> invalidContent() {
        return Stream.of(
                Arguments.of("---\ndescription: 描述\n---\n正文", "缺少名称（name）"),
                Arguments.of("---\nname: review\n---\n正文", "缺少描述（description）"),
                Arguments.of("---\nname: review\ndescription: 描述\n---\n \t", "缺少 Markdown 正文"),
                Arguments.of("---\nname: [unclosed\n---\n正文", "YAML 文件头格式错误"),
                Arguments.of("---\nname: review\nname: again\n---\n正文", "重复字段"));
    }

    @Test
    void appliesExistingNameAndDescriptionConstraints() throws Exception {
        http.perform(upload("---\nname: Invalid--Name\ndescription: " + "描".repeat(1025) + "\n---\n正文"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("name: 技能名称只能包含")))
                .andExpect(jsonPath("$.detail", containsString("description: 技能描述不能超过 1024")));
        verifyNoInteractions(mapper);
    }

    @Test
    void requiresExactlyOneFile() throws Exception {
        http.perform(multipart(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isBadRequest());
        http.perform(upload(MARKDOWN).file(file(MARKDOWN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("每次只能导入一个 SKILL.md 文件"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Tenant-Id", "X-User-Id"})
    void requiresBothIdentityHeaders(String onlyHeader) throws Exception {
        http.perform(multipart(URL).file(file(MARKDOWN)).header(onlyHeader, "10"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource({"0, 100", "10, -1", "abc, 100", "10, 9223372036854775808"})
    void rejectsInvalidIdentity(String tenantId, String userId) throws Exception {
        http.perform(multipart(URL).file(file(MARKDOWN))
                        .header("X-Tenant-Id", tenantId).header("X-User-Id", userId))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void reportsOversizedFileAs413() throws Exception {
        http.perform(multipart(URL)
                        .file(new MockMultipartFile("file", "SKILL.md", "text/markdown", new byte[1024 * 1024 + 1]))
                        .header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.detail").value("上传文件不得超过 1 MiB"));
        verifyNoInteractions(mapper);
    }

    @Test
    void reportsDuplicateNameWithoutExposingDatabaseDetails() throws Exception {
        when(mapper.insert(any(PersonalSkill.class))).thenThrow(new DuplicateKeyException("private database details"));

        http.perform(upload(MARKDOWN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("当前租户、用户下已存在同名技能"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "test"})
    void registersTestControllerOnlyInDevProfile(String profile) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.registerBean(SkillImportService.class, () -> imports);
            context.register(PersonalSkillImportController.class);
            context.refresh();

            assertThat(context.getBeansOfType(PersonalSkillImportController.class))
                    .hasSize(profile.equals("dev") ? 1 : 0);
        }
    }

    private static MockMultipartHttpServletRequestBuilder upload(String markdown) {
        return multipart(URL).file(file(markdown)).header("X-Tenant-Id", "10").header("X-User-Id", "100");
    }

    private static MockMultipartFile file(String markdown) {
        return new MockMultipartFile("file", "SKILL.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));
    }
}
