package com.laoshiren.agent.skills.agent;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.exception.SkillAgentCallException;
import com.laoshiren.agent.skills.param.SkillAgentCall;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用真实 ReActAgent、仓库适配、框架加载工具和事件流；仅替换远程模型与数据库。 */
class SkillAgentServiceTest {
    private static final SkillScope A = new SkillScope(10L, 100L);
    private static final String BODY = "# 审查\n\n请在回答中包含标记 SCOPE_CHECK，并检查租户和用户过滤。";
    private static final String SKILL_ID = "review_personal-db-t10-u100";
    private static ValidatorFactory validators;
    private IPersonalSkillService skills;
    private SkillAgentProperties properties;

    @BeforeAll
    static void configure() { validators = Validation.buildDefaultValidatorFactory(); }

    @AfterAll
    static void closeValidators() { validators.close(); }

    @BeforeEach
    void setUp() {
        skills = mock(IPersonalSkillService.class);
        properties = new SkillAgentProperties();
        properties.setTimeoutSeconds(5);
        when(skills.listEnabled(A)).thenReturn(List.of(skill(BODY)));
    }

    @Test
    void discoversLoadsExactInstructionsAndAnswersThroughRealAgentLoop() {
        AtomicInteger calls = new AtomicInteger();
        var actualToolOutput = new java.util.concurrent.atomic.AtomicReference<String>();
        Model model = model(messages -> {
            if (calls.getAndIncrement() == 0) {
                String prompt = messages.get(0).getTextContent();
                assertThat(prompt).contains("review", "检查代码归属", SKILL_ID).doesNotContain(BODY);
                return tool(SKILL_ID, "SKILL.md");
            }
            assertThat(toolOutput(messages)).contains(BODY);
            actualToolOutput.set(toolOutput(messages));
            return answer("SCOPE_CHECK：需要同时检查 tenant_id 和 owner_user_id。");
        });

        var result = service(() -> model).call(A, new SkillAgentCall("请检查查询的数据归属限制"));

        assertThat(result.callId()).isNotBlank();
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.answer()).contains("SCOPE_CHECK");
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.discoveredSkills()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("review");
            assertThat(s.skillId()).isEqualTo(SKILL_ID);
        });
        assertThat(result.skillLoads()).singleElement().satisfies(load -> {
            assertThat(load.skillName()).isEqualTo("review");
            assertThat(load.path()).isEqualTo("SKILL.md");
            assertThat(load.state()).isEqualTo("SUCCESS");
            assertThat(load.instructionsLoaded()).isTrue();
            assertThat(load.output()).contains(BODY);
            // 跟踪输出须与模型实际收到的内容完全一致，防止事件被重复拼接。
            assertThat(load.output()).isEqualTo(actualToolOutput.get());
            assertThat(load.terminalStateObserved()).isTrue();
            assertThat(load.observationEndReason()).isNull();
            assertThat(load.timing().endedAt()).isNotNull();
            assertThat(load.timing().elapsedMs()).isBetween(0L, result.timing().elapsedMs());
        });
        assertThat(result.steps()).extracting(s -> s.type()).containsSubsequence(
                "SKILLS_DISCOVERED", "MODEL_CALL", "SKILL_LOAD_REQUESTED", "SKILL_LOAD_FINISHED", "MODEL_CALL", "ANSWER");
        assertThat(result.steps().stream().filter(s -> "MODEL_CALL".equals(s.type())).count()).isEqualTo(2);
        assertThat(result.steps().stream().filter(s -> "ANSWER".equals(s.type())).count()).isEqualTo(1);
    }

    @Test
    void directoryDiscoveryAloneDoesNotClaimInstructionsWereLoaded() {
        var result = service(() -> model(messages -> answer("你好"))).call(A, new SkillAgentCall("你好"));

        assertThat(result.discoveredSkills()).hasSize(1);
        assertThat(result.skillLoads()).isEmpty();
        assertThat(result.answer()).isEqualTo("你好");
    }

    @Test
    void emptyCatalogCanAnswerWithoutInventingSkillUsage() {
        when(skills.listEnabled(A)).thenReturn(List.of());
        var result = service(() -> model(messages -> {
            assertThat(messages.get(0).getTextContent()).doesNotContain(SKILL_ID, BODY);
            return answer("当前没有可用技能。");
        })).call(A, new SkillAgentCall("有哪些技能"));

        assertThat(result.discoveredSkills()).isEmpty();
        assertThat(result.skillLoads()).isEmpty();
        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    @Test
    void failedResourceLoadIsNotMarkedAsLoadedInstructions() {
        AtomicInteger calls = new AtomicInteger();
        var result = service(() -> model(messages -> calls.getAndIncrement() == 0
                ? tool(SKILL_ID, "missing.txt") : answer("资源不存在")))
                .call(A, new SkillAgentCall("读取技能"));

        assertThat(result.skillLoads()).singleElement().satisfies(load -> {
            assertThat(load.instructionsLoaded()).isFalse();
            assertThat(load.state()).isEqualTo("ERROR");
            assertThat(load.output()).doesNotContain(BODY);
        });
    }

    @Test
    void sameNameFromAnotherIdentityNeverLoadsFirstUsersInstructions() {
        var b = new SkillScope(10L, 200L);
        when(skills.listEnabled(b)).thenReturn(List.of(skill("B_ONLY 正文")));
        AtomicInteger calls = new AtomicInteger();
        var result = service(() -> model(messages -> {
            if (calls.getAndIncrement() == 0) {
                assertThat(messages.get(0).getTextContent()).contains("review_personal-db-t10-u200")
                        .doesNotContain(SKILL_ID, BODY);
                return tool(SKILL_ID, "SKILL.md");
            }
            assertThat(toolOutput(messages)).doesNotContain(BODY, "B_ONLY 正文");
            return answer("无法读取该技能");
        })).call(b, new SkillAgentCall("尝试加载其他用户的技能"));

        assertThat(result.discoveredSkills()).singleElement().satisfies(s ->
                assertThat(s.skillId()).isEqualTo("review_personal-db-t10-u200"));
        assertThat(result.skillLoads()).singleElement().satisfies(load -> {
            assertThat(load.instructionsLoaded()).isFalse();
            assertThat(load.skillName()).isNull();
            assertThat(load.state()).isEqualTo("ERROR");
        });
    }

    @Test
    void separateCallsDoNotShareHistoryOrTraces() {
        var model = model(messages -> {
            assertThat(messages).hasSize(2);
            return answer("独立回答");
        });
        var service = service(() -> model);
        var first = service.call(A, new SkillAgentCall("第一条提问"));
        var second = service.call(A, new SkillAgentCall("第二条提问"));
        assertThat(first.callId()).isNotEqualTo(second.callId());
        assertThat(first.modelCalls()).isEqualTo(1);
        assertThat(second.modelCalls()).isEqualTo(1);
    }

    @Test
    void modelFailurePreservesCompletedSkillLoadAndHidesProviderError() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(() -> model(messages -> {
            if (calls.getAndIncrement() == 0) return tool(SKILL_ID, "SKILL.md");
            throw new IllegalStateException("private-provider-error");
        }));

        assertThatThrownBy(() -> service.call(A, new SkillAgentCall("审查代码")))
                .isInstanceOfSatisfying(SkillAgentCallException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(error.getMessage()).doesNotContain("private-provider-error");
                    assertThat(error.getCall().status()).isEqualTo("FAILED");
                    assertThat(error.getCall().skillLoads()).singleElement().satisfies(load ->
                            assertThat(load.instructionsLoaded()).isTrue());
                });
    }

    @Test
    void totalTimeoutCancelsModelAndReturnsTrace() {
        properties.setTimeoutSeconds(1);
        var cancelled = new AtomicInteger();
        Model stalled = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.<ChatResponse>never().doOnCancel(cancelled::incrementAndGet);
            }
            @Override public String getModelName() { return "stalled-test-model"; }
        };

        assertThatThrownBy(() -> service(() -> stalled).call(A, new SkillAgentCall("审查")))
                .isInstanceOfSatisfying(SkillAgentCallException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(error.getCall().status()).isEqualTo("TIMED_OUT");
                });
        assertThat(cancelled.get()).isPositive();
    }

    @Test
    void repositoryFailureDoesNotSilentlyRunWithEmptySkills() {
        when(skills.listEnabled(A)).thenThrow(new DataAccessResourceFailureException("private-db-error"));
        Model model = mock(Model.class);
        assertThatThrownBy(() -> service(() -> model).call(A, new SkillAgentCall("审查")))
                .isInstanceOfSatisfying(SkillAgentCallException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCall().status()).isEqualTo("REPOSITORY_UNAVAILABLE");
                });
        verifyNoInteractions(model);
    }

    @Test
    void missingModelConfigurationDoesNotPreventServiceConstruction() {
        var provider = new SkillAgentConfiguration().skillAgentModelProvider(properties);
        var service = service(provider);
        assertThatThrownBy(() -> service.call(A, new SkillAgentCall("审查")))
                .isInstanceOfSatisfying(SkillAgentCallException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessage()).contains("SKILLS_MODEL_BASE_URL");
                });
        verifyNoInteractions(skills);
    }

    @Test
    void configuredModelClientIsCreatedOnceWithoutMakingAnApiCall() {
        properties.setBaseUrl("http://localhost:9999/v1");
        properties.setModelName("local-test-model");
        properties.setApiKey("not-required");
        var provider = new SkillAgentConfiguration().skillAgentModelProvider(properties);
        var first = provider.getModel();
        assertThat(first.getModelName()).isEqualTo("local-test-model");
        assertThat(provider.getModel()).isSameAs(first);
    }

    @Test
    void iterationLimitIsReportedInsteadOfClaimingNormalCompletion() {
        properties.setMaxIters(1);
        var calls = new AtomicInteger();
        var result = service(() -> model(messages -> calls.getAndIncrement() == 0
                ? tool(SKILL_ID, "SKILL.md") : answer("已达到迭代上限，返回当前进度。")))
                .call(A, new SkillAgentCall("审查"));
        assertThat(result.status()).isEqualTo("MAX_ITERS");
        assertThat(result.steps()).extracting(s -> s.type()).contains("MAX_ITERS");
    }

    @Test
    void validatesRequestBeforeDatabaseOrModelAccess() {
        var provider = mock(SkillAgentModelProvider.class);
        var service = service(provider);
        assertThatThrownBy(() -> service.call(null, new SkillAgentCall("审查")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.call(A, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.call(A, new SkillAgentCall(" ")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> service.call(A, new SkillAgentCall("a".repeat(8001))))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(skills, provider);
    }

    private SkillAgentService service(SkillAgentModelProvider provider) {
        return new SkillAgentService(new PersonalSkillRepositoryFactory(skills), provider, properties,
                validators.getValidator());
    }

    static Model model(Function<List<Msg>, ChatResponse> reply) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> Flux.just(reply.apply(messages)));
            }
            @Override public String getModelName() { return "scripted-test-model"; }
        };
    }

    static ChatResponse tool(String skillId, String path) {
        return ChatResponse.builder().content(List.of(ToolUseBlock.builder().id("load-1")
                        .name("load_skill_through_path").input(Map.of("skillId", skillId, "path", path))
                        .content("{\"skillId\":\"" + skillId + "\",\"path\":\"" + path + "\"}").build()))
                .finishReason("tool_calls").build();
    }

    static ChatResponse answer(String text) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop").build();
    }

    static String toolOutput(List<Msg> messages) {
        return messages.stream().flatMap(m -> m.getContent().stream())
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                .flatMap(t -> t.getOutput().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat);
    }

    static PersonalSkill skill(String body) {
        var skill = new PersonalSkill();
        skill.setName("review");
        skill.setDescription("检查代码归属");
        skill.setInstructionsMd(body);
        return skill;
    }
}
