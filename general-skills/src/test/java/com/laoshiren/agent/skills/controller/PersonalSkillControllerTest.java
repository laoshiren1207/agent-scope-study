package com.laoshiren.agent.skills.controller;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.param.CreateSkillDraft;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalSkillControllerTest {
    private static final String URL = "/api/personal-skills";
    private static final SkillScope USER_A = new SkillScope(10L, 100L);
    private static final String DRAFT_JSON = """
            {"name":"spring-code-review","description":"检查 Spring 代码",
             "instructionsMd":"# 审查\\n检查事务边界。","extraFrontmatter":{"tags":["java"]}}
            """;

    private IPersonalSkillService service;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        service = mock(IPersonalSkillService.class);
        http = MockMvcBuilders.standaloneSetup(
                        new PersonalSkillController(service, new PersonalSkillRepositoryFactory(service)))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
    }

    @Test
    void createsDraftUsingHeaderScopeAndReturnsLocationAndFullDetail() throws Exception {
        when(service.createDraft(any(), any())).thenReturn(savedSkill());

        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .param("tenantId", "20").param("ownerUserId", "200")
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", URL + "/123"))
                .andExpect(jsonPath("$.id").value(123))
                .andExpect(jsonPath("$.tenantId").value(10))
                .andExpect(jsonPath("$.ownerUserId").value(100))
                .andExpect(jsonPath("$.name").value("spring-code-review"))
                .andExpect(jsonPath("$.description").value("检查 Spring 代码"))
                .andExpect(jsonPath("$.instructionsMd").value("# 审查\n检查事务边界。"))
                .andExpect(jsonPath("$.extraFrontmatter.tags[0]").value("java"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verify(service).createDraft(USER_A, new CreateSkillDraft(
                "spring-code-review", "检查 Spring 代码", "# 审查\n检查事务边界。",
                Map.of("tags", List.of("java"))));
    }

    @Test
    void readsOwnerDetail() throws Exception {
        when(service.getDetail(USER_A, 123L)).thenReturn(Optional.of(savedSkill()));

        http.perform(get(URL + "/123").header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(123))
                .andExpect(jsonPath("$.ownerUserId").value(100));

        verify(service).getDetail(USER_A, 123L);
    }

    @Test
    void repositoryListUsesEnabledSkillsAndCannotBeOverriddenByQueryParameters() throws Exception {
        var enabled = savedSkill();
        enabled.setStatus(PersonalSkillStatus.ENABLED);
        when(service.listEnabled(USER_A)).thenReturn(List.of(enabled));

        http.perform(get(URL + "/repository").header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .param("tenantId", "20").param("ownerUserId", "200").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("spring-code-review"));

        verify(service).listEnabled(USER_A);
    }

    @Test
    void repositoryLoadsFrameworkSkillByNameWithBodyAndMetadata() throws Exception {
        var enabled = savedSkill();
        enabled.setStatus(PersonalSkillStatus.ENABLED);
        when(service.findEnabledByName(USER_A, "spring-code-review")).thenReturn(Optional.of(enabled));

        http.perform(get(URL + "/repository/spring-code-review")
                        .header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("spring-code-review"))
                .andExpect(jsonPath("$.description").value("检查 Spring 代码"))
                .andExpect(jsonPath("$.skillContent").value("# 审查\n检查事务边界。"))
                .andExpect(jsonPath("$.metadata.tags[0]").value("java"))
                .andExpect(jsonPath("$.metadata.name").value("spring-code-review"))
                .andExpect(jsonPath("$.source").value("personal-db-t10-u100"))
                .andExpect(jsonPath("$.skillId").value("spring-code-review_personal-db-t10-u100"));

        verify(service).findEnabledByName(USER_A, "spring-code-review");
    }

    @ParameterizedTest
    @CsvSource({"10, 100", "10, 200", "20, 100"})
    void unavailableRepositorySkillsHaveEmptyListAndSame404(long tenantId, long userId) throws Exception {
        var scope = new SkillScope(tenantId, userId);
        when(service.listEnabled(scope)).thenReturn(List.of());
        when(service.findEnabledByName(scope, "spring-code-review")).thenReturn(Optional.empty());

        http.perform(get(URL + "/repository").header("X-Tenant-Id", tenantId).header("X-User-Id", userId))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        http.perform(get(URL + "/repository/spring-code-review")
                        .header("X-Tenant-Id", tenantId).header("X-User-Id", userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("技能不存在"))
                .andExpect(jsonPath("$.skillContent").doesNotExist());

        verify(service).findEnabledByName(scope, "spring-code-review");
    }

    @ParameterizedTest
    @CsvSource({"10, 200", "20, 100", "20, 200", "10, 100"})
    void missingAndOutOfScopeSkillsHaveSameNotFoundResponse(long tenantId, long userId) throws Exception {
        var scope = new SkillScope(tenantId, userId);
        when(service.getDetail(scope, 123L)).thenReturn(Optional.empty());

        http.perform(get(URL + "/123").header("X-Tenant-Id", tenantId).header("X-User-Id", userId)
                        .param("tenantId", "10").param("userId", "100").param("ownerUserId", "100"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("技能不存在"))
                .andExpect(jsonPath("$.instructionsMd").doesNotExist());

        verify(service).getDetail(scope, 123L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Tenant-Id", "X-User-Id"})
    void requiresBothIdentityHeaders(String onlyHeader) throws Exception {
        http.perform(post(URL).header(onlyHeader, "10")
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isBadRequest());
        http.perform(get(URL + "/123").header(onlyHeader, "10"))
                .andExpect(status().isBadRequest());
        http.perform(get(URL + "/repository").header(onlyHeader, "10"))
                .andExpect(status().isBadRequest());
        http.perform(get(URL + "/repository/review").header(onlyHeader, "10"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"0, 100", "-1, 100", "10, 0", "10, -1", "abc, 100", "10, abc",
            "9223372036854775808, 100"})
    void rejectsInvalidIdentityHeaders(String tenantId, String userId) throws Exception {
        http.perform(post(URL).header("X-Tenant-Id", tenantId).header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isBadRequest());
        http.perform(get(URL + "/123").header("X-Tenant-Id", tenantId).header("X-User-Id", userId))
                .andExpect(status().isBadRequest());
        http.perform(get(URL + "/repository").header("X-Tenant-Id", tenantId).header("X-User-Id", userId))
                .andExpect(status().isBadRequest());
        http.perform(get(URL + "/repository/review").header("X-Tenant-Id", tenantId).header("X-User-Id", userId))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidSkillIds(String skillId) throws Exception {
        http.perform(get(URL + "/" + skillId).header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidDraftWithFieldValidationMessages() throws Exception {
        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Invalid--Name","description":" ","instructionsMd":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("name:")))
                .andExpect(jsonPath("$.detail", containsString("description:")))
                .andExpect(jsonPath("$.detail", containsString("instructionsMd:")));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "[]", "{\"extraFrontmatter\":[]}"})
    void rejectsMissingOrMalformedJsonBody(String body) throws Exception {
        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("请求体必须为合法 JSON，且字段类型正确"));

        verifyNoInteractions(service);
    }

    @Test
    void reportsServiceArgumentValidationAsBadRequest() throws Exception {
        when(service.createDraft(any(), any()))
                .thenThrow(new IllegalArgumentException("扩展头部不能包含 name 或 description"));

        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("扩展头部不能包含 name 或 description"));
    }

    @Test
    void reportsDuplicateNameWithoutExposingDatabaseDetails() throws Exception {
        when(service.createDraft(any(), any())).thenThrow(new DuplicateKeyException("private database details"));

        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("当前租户、用户下已存在同名技能"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "test"})
    void registersTestControllerOnlyInDevProfile(String profile) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.registerBean(IPersonalSkillService.class, () -> service);
            context.register(PersonalSkillRepositoryFactory.class);
            context.register(PersonalSkillController.class);
            context.refresh();

            assertThat(context.getBeansOfType(PersonalSkillController.class))
                    .hasSize(profile.equals("dev") ? 1 : 0);
        }
    }

    private static PersonalSkill savedSkill() {
        var skill = new PersonalSkill();
        skill.setId(123L);
        skill.setTenantId(10L);
        skill.setOwnerUserId(100L);
        skill.setName("spring-code-review");
        skill.setDescription("检查 Spring 代码");
        skill.setInstructionsMd("# 审查\n检查事务边界。");
        skill.setExtraFrontmatter(Map.of("tags", List.of("java")));
        skill.setStatus(PersonalSkillStatus.DRAFT);
        skill.setRevision(1L);
        skill.setCreatedAt(OffsetDateTime.parse("2026-11-18T08:00:00Z"));
        skill.setUpdatedAt(skill.getCreatedAt());
        return skill;
    }
}
