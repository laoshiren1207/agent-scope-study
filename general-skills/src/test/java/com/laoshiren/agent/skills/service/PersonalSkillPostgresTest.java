package com.laoshiren.agent.skills.service;

import com.laoshiren.agent.skills.controller.PersonalSkillController;
import com.laoshiren.agent.skills.controller.PersonalSkillExceptionHandler;
import com.laoshiren.agent.skills.controller.PersonalSkillImportController;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.exception.SkillNotFoundException;
import com.laoshiren.agent.skills.exception.SkillRevisionConflictException;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.UpdateSkill;
import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;
import com.laoshiren.agent.skills.agent.SkillAgentProperties;
import com.laoshiren.agent.skills.agent.SkillAgentService;
import com.laoshiren.agent.skills.param.SkillAgentCall;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import jakarta.validation.Validator;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import com.laoshiren.agent.skills.param.CreateSkillDraft;
import com.laoshiren.agent.skills.param.SkillScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 显式提供专用 PostgreSQL 测试库；建表和测试写入均在测试事务中回滚。 */
@EnabledIfEnvironmentVariable(named = "SKILLS_PG_TEST_URL", matches = ".+")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.sql.init.mode=never")
@Transactional
@Sql("classpath:db/schema.sql")
class PersonalSkillPostgresTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SKILLS_PG_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv()
                .getOrDefault("SKILLS_PG_TEST_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv()
                .getOrDefault("SKILLS_PG_TEST_PASSWORD", "postgres"));
    }

    @Autowired IPersonalSkillService service;
    @Autowired SkillImportService imports;
    @Autowired PersonalSkillRepositoryFactory repositories;
    @Autowired Validator validator;
    @Autowired JdbcTemplate jdbc;

    private static final SkillScope USER_A = new SkillScope(10L, 100L);
    private static final SkillScope USER_B = new SkillScope(10L, 200L);
    private static final SkillScope OTHER_TENANT_A = new SkillScope(20L, 100L);
    private static final CreateSkillDraft DRAFT = new CreateSkillDraft(
            "spring-code-review", "检查 Spring 代码", "# 审查\n\n检查事务边界。\n",
            Map.of("tags", List.of("java", "spring"), "metadata", Map.of("version", 1)));

    @Test
    void agentLoadsInstructionsFromEnabledDatabaseSkill() {
        var created = service.createDraft(USER_A, DRAFT);
        service.enable(USER_A, created.getId(), 1L);
        service.createDraft(USER_A, new CreateSkillDraft("draft-only", "草稿", "不可加载", null));
        var calls = new AtomicInteger();
        String skillId = "spring-code-review_personal-db-t10-u100";
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (calls.getAndIncrement() == 0) {
                    assertThat(messages.get(0).getTextContent()).contains(skillId).doesNotContain("draft-only");
                    return Flux.just(ChatResponse.builder().content(List.of(ToolUseBlock.builder()
                                    .id("db-load").name("load_skill_through_path")
                                    .content("{\"skillId\":\"" + skillId + "\",\"path\":\"SKILL.md\"}").build()))
                            .finishReason("tool_calls").build());
                }
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder()
                        .text("请检查事务边界。").build())).finishReason("stop").build());
            }
            @Override public String getModelName() { return "database-integration-test-model"; }
        };
        var agent = new SkillAgentService(repositories, () -> model, new SkillAgentProperties(), validator);
        var result = agent.call(USER_A, new SkillAgentCall("请使用代码审查技能检查事务"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.discoveredSkills()).hasSize(1);
        assertThat(result.skillLoads()).singleElement().satisfies(load -> {
            assertThat(load.skillId()).isEqualTo(skillId);
            assertThat(load.instructionsLoaded()).isTrue();
            assertThat(load.output()).contains(DRAFT.instructionsMd());
        });
        assertThat(result.answer()).isEqualTo("请检查事务边界。");
    }

    @Test
    void repositoryReadsOnlyEnabledOwnedSkillsAndReflectsLifecycleChanges() throws Exception {
        var repository = repositories.forScope(USER_A);
        var owned = service.createDraft(USER_A, DRAFT);
        service.createDraft(USER_A, new CreateSkillDraft("draft-only", "草稿", "草稿正文", null));
        var disabled = service.createDraft(USER_A, new CreateSkillDraft("disabled-only", "停用", "停用正文", null));
        service.disable(USER_A, disabled.getId(), 1L);
        var otherUser = service.createDraft(USER_B, new CreateSkillDraft(DRAFT.name(), "B 的描述", "B 的正文", null));
        service.enable(USER_B, otherUser.getId(), 1L);
        var otherTenant = service.createDraft(OTHER_TENANT_A, DRAFT);
        service.enable(OTHER_TENANT_A, otherTenant.getId(), 1L);

        assertThat(repository.getAllSkillNames()).isEmpty();
        assertThat(repository.getAllSkills()).isEmpty();
        assertThat(repository.getSkill(DRAFT.name())).isNull();
        assertThat(repository.skillExists(DRAFT.name())).isFalse();
        assertThat(repositories.forScope(USER_B).getSkill(DRAFT.name()).getSkillContent()).isEqualTo("B 的正文");
        assertThat(repositories.forScope(OTHER_TENANT_A).getAllSkillNames()).containsExactly(DRAFT.name());

        var enabled = service.enable(USER_A, owned.getId(), 1L);
        assertThat(repository.getAllSkillNames()).containsExactly(DRAFT.name());
        assertThat(repository.getAllSkills()).hasSize(1);
        var loaded = repository.getSkill(DRAFT.name());
        assertThat(loaded.getSkillContent()).isEqualTo(DRAFT.instructionsMd());
        assertThat(loaded.getMetadata()).containsAllEntriesOf(DRAFT.extraFrontmatter());
        assertThat(repository.skillExists(DRAFT.name())).isTrue();

        var http = MockMvcBuilders.standaloneSetup(new PersonalSkillController(service, repositories))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
        http.perform(get("/api/personal-skills/repository")
                        .header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(DRAFT.name()));
        http.perform(get("/api/personal-skills/repository/" + DRAFT.name())
                        .header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillContent").value(DRAFT.instructionsMd()));
        for (String hidden : List.of("draft-only", "disabled-only", "missing")) {
            http.perform(get("/api/personal-skills/repository/" + hidden)
                            .header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("技能不存在"));
        }
        http.perform(get("/api/personal-skills/repository/" + DRAFT.name())
                        .header("X-Tenant-Id", "30").header("X-User-Id", "100"))
                .andExpect(status().isNotFound());

        var edited = service.update(USER_A, owned.getId(), new UpdateSkill(enabled.getRevision(),
                "renamed-review", "新描述", "新正文", Map.of("tags", List.of("updated"))));
        assertThat(repository.getSkill(DRAFT.name())).isNull();
        assertThat(repository.getSkill("renamed-review").getSkillContent()).isEqualTo("新正文");
        assertThat(repository.getAllSkillNames()).containsExactly("renamed-review");
        var stopped = service.disable(USER_A, owned.getId(), edited.getRevision());
        assertThat(repository.getSkill("renamed-review")).isNull();
        assertThat(repository.skillExists("renamed-review")).isFalse();
        assertThat(repository.getAllSkills()).isEmpty();
        var restarted = service.enable(USER_A, owned.getId(), stopped.getRevision());
        assertThat(repository.skillExists("renamed-review")).isTrue();
        service.delete(USER_A, owned.getId(), restarted.getRevision());
        assertThat(repository.getAllSkillNames()).isEmpty();
        assertThat(repository.getSkill("renamed-review")).isNull();
        assertThat(repositories.forScope(USER_B).getSkill(DRAFT.name()).getSkillContent()).isEqualTo("B 的正文");
    }

    @Test
    void importsMarkdownIntoPostgresAsOwnedDraftAndHidesItFromOtherScopes() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(
                        new PersonalSkillImportController(imports), new PersonalSkillController(service, repositories))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
        var file = new MockMultipartFile("file", "SKILL.md", "text/markdown", """
                ---
                name: imported-review
                description: 导入的代码审查技能
                tags: [java, spring]
                metadata:
                  version: 1
                  optional: null
                tenantId: 20
                ownerUserId: 200
                status: ENABLED
                revision: 99
                ---
                # 审查

                检查事务边界。
                """.getBytes(StandardCharsets.UTF_8));
        String location = http.perform(multipart("/api/personal-skills/import").file(file)
                        .header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(10))
                .andExpect(jsonPath("$.ownerUserId").value(100))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).isNotNull();
        http.perform(get(location).header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("imported-review"))
                .andExpect(jsonPath("$.description").value("导入的代码审查技能"))
                .andExpect(jsonPath("$.instructionsMd").value("# 审查\n\n检查事务边界。\n"))
                .andExpect(jsonPath("$.extraFrontmatter.tags[1]").value("spring"))
                .andExpect(jsonPath("$.extraFrontmatter.metadata.version").value(1));
        http.perform(get(location).header("X-Tenant-Id", "10").header("X-User-Id", "200"))
                .andExpect(status().isNotFound());
        http.perform(get(location).header("X-Tenant-Id", "20").header("X-User-Id", "100"))
                .andExpect(status().isNotFound());
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        var saved = service.getDetail(USER_A, id).orElseThrow();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        var metadata = (Map<?, ?>) saved.getExtraFrontmatter().get("metadata");
        assertThat(metadata.containsKey("optional")).isTrue();
        assertThat(metadata.get("optional")).isNull();
    }

    @Test
    void httpApiCreatesForAAndHidesSameIdFromOtherUsersAndTenants() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(new PersonalSkillController(service, repositories))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
        String location = http.perform(post("/api/personal-skills")
                        .header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"spring-code-review","description":"检查 Spring 代码",
                                 "instructionsMd":"检查事务边界。"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(100))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).isNotNull();
        http.perform(get(location).header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructionsMd").value("检查事务边界。"));
        http.perform(get(location).header("X-Tenant-Id", "10").header("X-User-Id", "200"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("技能不存在"));
        http.perform(get(location).header("X-Tenant-Id", "20").header("X-User-Id", "100"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("技能不存在"));
    }

    @Test
    void ownerCanCreateAndReadWhileOtherUsersAndTenantsCannotReadSameId() {
        var created = service.createDraft(USER_A, DRAFT);

        assertThat(created.getId()).isPositive();
        assertThat(created.getTenantId()).isEqualTo(USER_A.tenantId());
        assertThat(created.getOwnerUserId()).isEqualTo(USER_A.userId());
        assertThat(created.getName()).isEqualTo(DRAFT.name());
        assertThat(created.getDescription()).isEqualTo(DRAFT.description());
        assertThat(created.getInstructionsMd()).isEqualTo(DRAFT.instructionsMd());
        assertThat(created.getExtraFrontmatter()).isEqualTo(DRAFT.extraFrontmatter());
        assertThat(created.getStatus()).isEqualTo(PersonalSkillStatus.DRAFT);
        assertThat(created.getRevision()).isEqualTo(1L);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(service.getDetail(USER_A, created.getId())).contains(created);
        assertThat(service.getDetail(USER_B, created.getId())).isEmpty();
        assertThat(service.getDetail(OTHER_TENANT_A, created.getId())).isEmpty();
        assertThat(service.getDetail(new SkillScope(20L, 200L), created.getId())).isEmpty();
        assertThat(service.getDetail(USER_A, Long.MAX_VALUE)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM public.personal_skill WHERE id = ?",
                String.class, created.getId())).isEqualTo("draft");
    }

    @Test
    void sameNameCanBeUsedInDifferentUserAndTenantScopes() {
        var a = service.createDraft(USER_A, DRAFT);
        var b = service.createDraft(USER_B, DRAFT);
        var otherTenant = service.createDraft(OTHER_TENANT_A, DRAFT);

        assertThat(List.of(a.getId(), b.getId(), otherTenant.getId())).doesNotHaveDuplicates();
        assertThat(service.getDetail(USER_B, b.getId())).contains(b);
        assertThat(service.getDetail(OTHER_TENANT_A, otherTenant.getId())).contains(otherTenant);
        assertThat(service.getDetail(USER_A, b.getId())).isEmpty();
        assertThat(service.getDetail(USER_A, otherTenant.getId())).isEmpty();
    }

    @Test
    void duplicateNameWithinSameScopeIsRejected() {
        service.createDraft(USER_A, DRAFT);

        assertThatThrownBy(() -> service.createDraft(USER_A, DRAFT)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void missingFrontmatterIsStoredAsEmptyJsonObject() {
        var created = service.createDraft(USER_A, new CreateSkillDraft(
                DRAFT.name(), DRAFT.description(), DRAFT.instructionsMd(), null));

        assertThat(created.getExtraFrontmatter()).isEmpty();
        assertThat(service.getDetail(USER_A, created.getId()).orElseThrow().getExtraFrontmatter()).isEmpty();
    }

    @Test
    void managesLifecycleAndRejectsStaleEditsStatusChangesAndDeletes() {
        var created = service.createDraft(USER_A, DRAFT);
        long id = created.getId();
        var enabled = service.enable(USER_A, id, 1L);
        assertThat(enabled.getStatus()).isEqualTo(PersonalSkillStatus.ENABLED);
        assertThat(enabled.getRevision()).isEqualTo(2L);
        assertThat(enabled.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(enabled.getUpdatedAt()).isAfter(created.getUpdatedAt());

        var content = new UpdateSkill(2L, "updated-skill", "新描述", "# 新正文\n检查并发。",
                Map.of("metadata", Map.of("version", 2), "tags", List.of("concurrency")));
        var edited = service.update(USER_A, id, content);
        assertThat(edited.getRevision()).isEqualTo(3L);
        assertThat(edited.getStatus()).isEqualTo(PersonalSkillStatus.ENABLED);
        assertThat(edited.getName()).isEqualTo(content.name());
        assertThat(edited.getDescription()).isEqualTo(content.description());
        assertThat(edited.getInstructionsMd()).isEqualTo(content.instructionsMd());
        assertThat(edited.getExtraFrontmatter()).isEqualTo(content.extraFrontmatter());
        assertThat(edited.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(edited.getUpdatedAt()).isAfter(enabled.getUpdatedAt());

        assertThatThrownBy(() -> service.update(USER_A, id,
                new UpdateSkill(2L, "stale-name", "过期描述", "过期正文", null)))
                .isInstanceOf(SkillRevisionConflictException.class);
        assertThatThrownBy(() -> service.enable(USER_A, id, 2L)).isInstanceOf(SkillRevisionConflictException.class);
        assertThatThrownBy(() -> service.disable(USER_A, id, 2L)).isInstanceOf(SkillRevisionConflictException.class);
        assertThatThrownBy(() -> service.delete(USER_A, id, 2L)).isInstanceOf(SkillRevisionConflictException.class);
        assertThat(service.getDetail(USER_A, id)).contains(edited);

        var disabled = service.disable(USER_A, id, 3L);
        assertThat(disabled.getStatus()).isEqualTo(PersonalSkillStatus.DISABLED);
        assertThat(disabled.getRevision()).isEqualTo(4L);
        assertThat(disabled.getUpdatedAt()).isAfter(edited.getUpdatedAt());
        var repeated = service.disable(USER_A, id, 4L);
        assertThat(repeated.getRevision()).isEqualTo(5L);
        service.delete(USER_A, id, 5L);
        assertThat(service.getDetail(USER_A, id)).isEmpty();
        assertThat(service.list(USER_A, new SkillListQuery(1, 20, null)).total()).isZero();
        assertThat(service.createDraft(USER_A, new CreateSkillDraft(
                content.name(), content.description(), content.instructionsMd(), null)).getId()).isNotEqualTo(id);
    }

    @ParameterizedTest
    @CsvSource({"10, 200", "20, 100", "20, 200"})
    void isolatesEveryManagementOperationByTenantAndUser(long tenantId, long userId) {
        var created = service.createDraft(USER_A, DRAFT);
        var otherScope = new SkillScope(tenantId, userId);
        long id = created.getId();
        var page = service.list(otherScope, new SkillListQuery(1, 20, null));
        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
        assertThatThrownBy(() -> service.update(otherScope, id,
                new UpdateSkill(1L, "other-name", "他人描述", "他人正文", null)))
                .isInstanceOf(SkillNotFoundException.class);
        assertThatThrownBy(() -> service.enable(otherScope, id, 1L)).isInstanceOf(SkillNotFoundException.class);
        assertThatThrownBy(() -> service.disable(otherScope, id, 1L)).isInstanceOf(SkillNotFoundException.class);
        assertThatThrownBy(() -> service.delete(otherScope, id, 1L)).isInstanceOf(SkillNotFoundException.class);
        assertThat(service.getDetail(USER_A, id)).contains(created);
    }

    @Test
    void listsOwnedSkillsWithStablePaginationAndStatusFilteredTotals() {
        var first = service.createDraft(USER_A, new CreateSkillDraft("first-skill", "描述", "正文", null));
        var second = service.createDraft(USER_A, new CreateSkillDraft("second-skill", "描述", "正文",
                Map.of("tags", List.of("java"))));
        var third = service.createDraft(USER_A, new CreateSkillDraft("third-skill", "描述", "正文", null));
        service.createDraft(USER_B, DRAFT);
        service.createDraft(OTHER_TENANT_A, DRAFT);
        var enabled = service.enable(USER_A, second.getId(), 1L);

        var page1 = service.list(USER_A, new SkillListQuery(1, 2, null));
        assertThat(page1.total()).isEqualTo(3);
        assertThat(page1.items()).containsExactly(enabled, third);
        var page2 = service.list(USER_A, new SkillListQuery(2, 2, null));
        assertThat(page2.total()).isEqualTo(3);
        assertThat(page2.items()).containsExactly(first);
        var page3 = service.list(USER_A, new SkillListQuery(3, 2, null));
        assertThat(page3.total()).isEqualTo(3);
        assertThat(page3.items()).isEmpty();
        var filtered = service.list(USER_A, new SkillListQuery(1, 20, PersonalSkillStatus.ENABLED));
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.items()).containsExactly(enabled);
        assertThat(service.list(USER_A, new SkillListQuery(1, 20, PersonalSkillStatus.DRAFT)).items())
                .containsExactly(third, first);
        assertThat(service.list(USER_B, new SkillListQuery(1, 20, null)).total()).isEqualTo(1);
    }

    @Test
    void rejectsRenamingToExistingNameWithinSameScope() {
        service.createDraft(USER_A, DRAFT);
        var other = service.createDraft(USER_A, new CreateSkillDraft("other-skill", "描述", "正文", null));

        assertThatThrownBy(() -> service.update(USER_A, other.getId(),
                new UpdateSkill(1L, DRAFT.name(), "描述", "正文", null)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void httpManagementUsesDatabaseRevisionsAndPreservesNewContentAfterConflict() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(new PersonalSkillController(service, repositories))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
        long id = service.createDraft(USER_A, DRAFT).getId();
        String url = "/api/personal-skills/" + id;
        String update = """
                {"revision":1,"name":"edited-skill","description":"新描述","instructionsMd":"新正文"}
                """;
        http.perform(put(url).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        http.perform(put(url).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isConflict());
        http.perform(get(url).header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(jsonPath("$.instructionsMd").value("新正文"))
                .andExpect(jsonPath("$.revision").value(2));
        http.perform(get("/api/personal-skills").header("X-Tenant-Id", "10").header("X-User-Id", "200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        http.perform(post(url + "/enable").header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.status").value("ENABLED"));
        http.perform(post(url + "/disable").header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(4));
        http.perform(delete(url).header("X-Tenant-Id", "10").header("X-User-Id", "100").param("revision", "3"))
                .andExpect(status().isConflict());
        http.perform(delete(url).header("X-Tenant-Id", "10").header("X-User-Id", "100").param("revision", "4"))
                .andExpect(status().isNoContent());
        http.perform(get(url).header("X-Tenant-Id", "10").header("X-User-Id", "100"))
                .andExpect(status().isNotFound());
    }
}
