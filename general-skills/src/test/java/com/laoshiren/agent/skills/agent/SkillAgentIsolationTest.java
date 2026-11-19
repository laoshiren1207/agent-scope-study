package com.laoshiren.agent.skills.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.exception.SkillAgentCallException;
import com.laoshiren.agent.skills.mapper.PersonalSkillMapper;
import com.laoshiren.agent.skills.param.SkillAgentCall;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;
import com.laoshiren.agent.skills.result.SkillAgentCallResult;
import com.laoshiren.agent.skills.service.impl.PersonalSkillServiceImpl;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.SkillBox;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static com.laoshiren.agent.skills.agent.SkillAgentServiceTest.answer;
import static com.laoshiren.agent.skills.agent.SkillAgentServiceTest.tool;
import static com.laoshiren.agent.skills.agent.SkillAgentServiceTest.toolOutput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实执行服务、技能仓库、ReActAgent 和内置加载工具，仅用测试替身替换数据库及远程模型。
 * 共享同一个服务和模型，用可控的响应闸门保证并发调用确实重叠，不依赖 sleep 碰运气。
 */
@Timeout(25)
class SkillAgentIsolationTest {
    private static final SkillScope A = new SkillScope(10L, 100L);
    private static final SkillScope B = new SkillScope(10L, 200L);
    private static final SkillScope OTHER_TENANT_A = new SkillScope(20L, 100L);
    private static final String A_BODY = "A_ONLY_V1：仅用户 A 可见的审查指令。";
    private static final String A_NEW_BODY = "A_ONLY_V2：用户 A 修改后的新指令。";
    private static final String B_BODY = "B_ONLY：仅用户 B 可见的审查指令。";
    private static final String OTHER_BODY = "OTHER_TENANT_ONLY：另一个租户的指令。";
    private static ValidatorFactory validators;

    @BeforeAll
    static void configure() {
        validators = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidators() {
        validators.close();
    }

    @Test
    void alternatingUsersAndTenantsHaveIndependentSkillsConversationsAndSessions() throws Exception {
        try (var fixture = new Fixture()) {
            var requests = List.of(
                    fixture.expect("A-first-private-question", A, A_BODY),
                    fixture.expect("B-first-private-question", B, B_BODY),
                    fixture.expect("A-second-private-question", A, A_BODY),
                    fixture.expect("B-second-private-question", B, B_BODY),
                    fixture.expect("other-tenant-private-question", OTHER_TENANT_A, OTHER_BODY));
            var results = requests.stream().map(fixture::call).toList();

            assertThat(results).extracting(SkillAgentCallResult::callId).doesNotHaveDuplicates();
            for (int i = 0; i < requests.size(); i++) {
                fixture.assertResult(requests.get(i), results.get(i));
            }
            assertThat(requests).extracting(r -> r.context.getAgentState()).doesNotHaveDuplicates();
            verify(fixture.mapper, times(2)).listEnabledScoped(A);
            verify(fixture.mapper, times(2)).listEnabledScoped(B);
            verify(fixture.mapper).listEnabledScoped(OTHER_TENANT_A);
            fixture.assertCapturedDirectoriesRemoved(5);
        }
    }

    @ParameterizedTest
    @CsvSource({"10, 200", "20, 100"})
    void overlappingRequestsDoNotShareSkillsHistorySessionOrWorkDirectory(long tenantId, long userId) throws Exception {
        SkillScope secondScope = new SkillScope(tenantId, userId);
        String secondBody = secondScope.equals(B) ? B_BODY : OTHER_BODY;
        try (var fixture = new Fixture()) {
            var first = fixture.expect("concurrent-A-question", A, A_BODY).pause();
            var second = fixture.expect("concurrent-other-question", secondScope, secondBody).pause();
            Future<SkillAgentCallResult> runningA = fixture.submit(first);
            first.awaitEntered();
            Path firstDir = fixture.onlyDirectory();
            Future<SkillAgentCallResult> runningB = fixture.submit(second);
            second.awaitEntered();
            Path secondDir = fixture.directories.paths.stream().filter(p -> !p.equals(firstDir)).findFirst().orElseThrow();

            assertThat(runningA.isDone()).isFalse();
            assertThat(runningB.isDone()).isFalse();
            assertThat(firstDir).isDirectory();
            assertThat(secondDir).isDirectory().isNotEqualTo(firstDir);
            assertThat(first.context.getSessionId()).isNotEqualTo(second.context.getSessionId());
            assertThat(first.context.getAgentState()).isNotSameAs(second.context.getAgentState());

            first.release();
            var resultA = runningA.get(5, TimeUnit.SECONDS);
            fixture.assertResult(first, resultA);
            assertThat(firstDir).doesNotExist();
            // A 的完成与资源清理不能删除 B 的工作目录或取消 B 的请求。
            assertThat(secondDir).isDirectory();
            assertThat(runningB.isDone()).isFalse();

            second.release();
            var resultB = runningB.get(5, TimeUnit.SECONDS);
            fixture.assertResult(second, resultB);
            // 故意让两边模型返回相同 toolCallId，确认 trace 仍然各自独立。
            assertThat(resultA.skillLoads().get(0).toolCallId()).isEqualTo(resultB.skillLoads().get(0).toolCallId());
            assertThat(resultA.skillLoads().get(0).output()).doesNotContain(secondBody);
            assertThat(resultB.skillLoads().get(0).output()).doesNotContain(A_BODY);
            fixture.assertCapturedDirectoriesRemoved(2);
        }
    }

    @Test
    void nextRequestSeesEditedDisabledAndReenabledStateWithoutChangingOtherUser() throws Exception {
        try (var fixture = new Fixture()) {
            var original = fixture.expect("before-edit", A, A_BODY);
            var oldResult = fixture.call(original);
            fixture.assertResult(original, oldResult);

            // 模拟数据库已提交的新内容；同一个服务下一次必须重新查询。
            fixture.publish(A, A_NEW_BODY);
            var edited = fixture.expect("after-edit", A, A_NEW_BODY);
            fixture.assertResult(edited, fixture.call(edited));
            assertThat(oldResult.skillLoads().get(0).output()).contains(A_BODY).doesNotContain(A_NEW_BODY);

            fixture.publish(A, null);
            var disabled = fixture.expect("after-disable-forced-load", A, null);
            fixture.assertResult(disabled, fixture.call(disabled));
            var other = fixture.expect("B-after-A-disable", B, B_BODY);
            fixture.assertResult(other, fixture.call(other));

            fixture.publish(A, A_NEW_BODY);
            var reenabled = fixture.expect("after-reenable", A, A_NEW_BODY);
            fixture.assertResult(reenabled, fixture.call(reenabled));
            verify(fixture.mapper, times(4)).listEnabledScoped(A);
            // 停用后的空目录不会触发框架上传，因此这里只有四个可观察的上传目录。
            fixture.assertCapturedDirectoriesRemoved(4);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inFlightRequestKeepsSnapshotWhileNextRequestSeesCommittedChange(boolean disable) throws Exception {
        try (var fixture = new Fixture()) {
            var inFlight = fixture.expect("inflight-old-content", A, A_BODY).pause();
            Future<SkillAgentCallResult> oldCall = fixture.submit(inFlight);
            inFlight.awaitEntered();
            Path oldDir = fixture.onlyDirectory();

            fixture.publish(A, disable ? null : A_NEW_BODY);
            var next = fixture.expect("next-after-change", A, disable ? null : A_NEW_BODY);
            var nextResult = fixture.call(next);
            fixture.assertResult(next, nextResult);
            assertThat(oldCall.isDone()).isFalse();
            assertThat(oldDir).isDirectory();

            // 旧请求在变更后才真正执行加载，仍须使用已获取的目录和正文快照。
            inFlight.release();
            var oldResult = oldCall.get(5, TimeUnit.SECONDS);
            fixture.assertResult(inFlight, oldResult);
            assertThat(oldResult.callId()).isNotEqualTo(nextResult.callId());
            verify(fixture.mapper, times(2)).listEnabledScoped(A);
            fixture.assertCapturedDirectoriesRemoved(disable ? 1 : 2);
        }
    }

    @Test
    void failedRequestCleansItsDirectoryWithoutInterruptingOtherUser() throws Exception {
        try (var fixture = new Fixture()) {
            var failing = fixture.expect("A-model-failure", A, A_BODY).pause();
            failing.failAfterLoad = true;
            var surviving = fixture.expect("B-survives-A-failure", B, B_BODY).pause();
            Future<SkillAgentCallResult> first = fixture.submit(failing);
            failing.awaitEntered();
            Path firstDir = fixture.onlyDirectory();
            Future<SkillAgentCallResult> second = fixture.submit(surviving);
            surviving.awaitEntered();
            Path secondDir = fixture.directories.paths.stream().filter(p -> !p.equals(firstDir)).findFirst().orElseThrow();

            failing.release();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                    .cause().isInstanceOfSatisfying(SkillAgentCallException.class, error -> {
                        assertThat(error.getCall().status()).isEqualTo("FAILED");
                        assertThat(error.getCall().skillLoads()).singleElement().satisfies(load ->
                                assertThat(load.output()).contains(A_BODY).doesNotContain(B_BODY));
                    });
            assertThat(firstDir).doesNotExist();
            assertThat(secondDir).isDirectory();
            assertThat(second.isDone()).isFalse();

            surviving.release();
            fixture.assertResult(surviving, second.get(5, TimeUnit.SECONDS));
            fixture.assertCapturedDirectoriesRemoved(2);
        }
    }

    @Test
    void timeoutCleansOnlyItsOwnResourcesAndCancelsItsModelSubscription() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.properties.setTimeoutSeconds(1);
            var stalled = fixture.expect("A-timeout", A, A_BODY).pause();
            Future<SkillAgentCallResult> first = fixture.submit(stalled);
            stalled.awaitEntered();
            var other = fixture.expect("B-healthy-independent-request", B, B_BODY);
            fixture.assertResult(other, fixture.call(other));

            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                    .cause().isInstanceOfSatisfying(SkillAgentCallException.class, error ->
                            assertThat(error.getCall().status()).isEqualTo("TIMED_OUT"));
            assertThat(stalled.cancelled.get()).isEqualTo(1);
            fixture.assertCapturedDirectoriesRemoved(2);
        }
    }

    private static String skillId(SkillScope scope) {
        return "review_personal-db-t" + scope.tenantId() + "-u" + scope.userId();
    }

    private static final class Request {
        final String question;
        final SkillScope scope;
        final String body;
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final Sinks.One<Void> gate = Sinks.one();
        volatile RuntimeContext context;
        boolean paused;
        boolean failAfterLoad;

        Request(String question, SkillScope scope, String body) {
            this.question = question;
            this.scope = scope;
            this.body = body;
        }

        Request pause() {
            paused = true;
            return this;
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("请求应进入模型阶段：%s", question).isTrue();
        }

        void release() {
            gate.tryEmitEmpty();
        }
    }

    /**
     * 捕获本测试实际触发的技能上传目录，验证文件存在及清理结果；空技能目录不会触发上传。
     * 不扫描或删除其他请求的目录。
     */
    private static final class DirectoryCapture extends AppenderBase<ILoggingEvent> {
        final ConcurrentLinkedQueue<Path> paths = new ConcurrentLinkedQueue<>();

        @Override
        protected void append(ILoggingEvent event) {
            if ("Created upload directory: {}".equals(event.getMessage())) {
                paths.add(Path.of(event.getArgumentArray()[0].toString()).getParent());
            }
        }
    }

    private static final class Fixture implements Model, AutoCloseable {
        final PersonalSkillMapper mapper = mock(PersonalSkillMapper.class);
        final Map<SkillScope, List<PersonalSkill>> rows = new ConcurrentHashMap<>();
        final Map<String, Request> requests = new ConcurrentHashMap<>();
        final SkillAgentProperties properties = new SkillAgentProperties();
        final SkillAgentService service;
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final DirectoryCapture directories = new DirectoryCapture();
        final Logger skillLogger = (Logger) LoggerFactory.getLogger(SkillBox.class);

        Fixture() {
            properties.setTimeoutSeconds(15);
            // 查询边界替身返回该 scope 当前已启用的行；SQL 归属过滤由 Mapper 测试另行验证。
            when(mapper.listEnabledScoped(any(SkillScope.class)))
                    .thenAnswer(call -> rows.getOrDefault(call.getArgument(0), List.of()));
            var skills = new PersonalSkillServiceImpl(mapper, validators.getValidator());
            service = new SkillAgentService(new PersonalSkillRepositoryFactory(skills), () -> this,
                    properties, validators.getValidator());
            publish(A, A_BODY);
            publish(B, B_BODY);
            publish(OTHER_TENANT_A, OTHER_BODY);
            directories.start();
            skillLogger.addAppender(directories);
        }

        void publish(SkillScope scope, String body) {
            if (body == null) {
                rows.put(scope, List.of());
                return;
            }
            var row = new PersonalSkill();
            row.setId(scope.userId());
            row.setTenantId(scope.tenantId());
            row.setOwnerUserId(scope.userId());
            row.setName("review");
            row.setDescription("当前用户的审查技能");
            row.setInstructionsMd(body);
            row.setStatus(PersonalSkillStatus.ENABLED);
            row.setRevision(body.equals(A_NEW_BODY) ? 3L : 2L);
            rows.put(scope, List.of(row));
        }

        Request expect(String question, SkillScope scope, String body) {
            var request = new Request(question, scope, body);
            requests.put(question, request);
            return request;
        }

        SkillAgentCallResult call(Request request) {
            return service.call(request.scope, new SkillAgentCall(request.question));
        }

        Future<SkillAgentCallResult> submit(Request request) {
            return workers.submit(() -> call(request));
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.deferContextual(context -> {
                // 每一轮都检查输入历史，只允许当前请求的一条用户消息。
                List<String> userMessages = messages.stream().filter(m -> m.getRole() == MsgRole.USER)
                        .map(Msg::getTextContent).toList();
                assertThat(userMessages).hasSize(1);
                Request request = requests.get(userMessages.get(0));
                assertThat(request).isNotNull();
                RuntimeContext runtime = context.get(AgentBase.RUNTIME_CONTEXT_KEY);
                assertThat(runtime.getUserId()).isEqualTo(
                        "tenant-" + request.scope.tenantId() + "-user-" + request.scope.userId());
                assertThat(runtime.getSessionId()).isNotBlank();
                assertThat(runtime.getAgentState()).isNotNull();
                if (request.context == null) request.context = runtime;
                assertThat(runtime).isSameAs(request.context);

                String allText = messages.stream().map(Msg::getTextContent).reduce("", String::concat);
                for (String body : List.of(A_BODY, A_NEW_BODY, B_BODY, OTHER_BODY)) {
                    if (!body.equals(request.body)) {
                        assertThat(allText + toolOutput(messages)).doesNotContain(body);
                    }
                }
                for (String question : requests.keySet()) {
                    if (!question.equals(request.question)) assertThat(allText).doesNotContain(question);
                }

                if (request.modelCalls.getAndIncrement() == 0) {
                    assertThat(messages).hasSize(2);
                    String prompt = messages.get(0).getTextContent();
                    for (SkillScope scope : List.of(A, B, OTHER_TENANT_A)) {
                        if (scope.equals(request.scope) && request.body != null) {
                            assertThat(prompt).contains(skillId(scope));
                        } else {
                            assertThat(prompt).doesNotContain(skillId(scope));
                        }
                    }
                    // 停用后仍主动请求旧 ID，确认正文加载通道也无法取得停用技能。
                    ChatResponse load = tool(skillId(request.scope), "SKILL.md");
                    request.entered.countDown();
                    return request.paused
                            ? request.gate.asMono().thenMany(Flux.just(load)).doOnCancel(request.cancelled::incrementAndGet)
                            : Flux.just(load);
                }

                String output = toolOutput(messages);
                if (request.body != null) assertThat(output).contains(request.body);
                else assertThat(output).doesNotContain(A_BODY, A_NEW_BODY, B_BODY, OTHER_BODY);
                if (request.failAfterLoad) return Flux.error(new IllegalStateException("test-model-failure"));
                return Flux.just(answer("answer-for:" + request.question));
            });
        }

        @Override
        public String getModelName() {
            return "shared-isolation-test-model";
        }

        void assertResult(Request request, SkillAgentCallResult result) {
            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.answer()).isEqualTo("answer-for:" + request.question);
            assertThat(result.callId()).isEqualTo(request.context.getSessionId());
            assertThat(result.modelCalls()).isEqualTo(2);
            if (request.body == null) assertThat(result.discoveredSkills()).isEmpty();
            else assertThat(result.discoveredSkills()).extracting(s -> s.skillId()).containsExactly(skillId(request.scope));
            assertThat(result.skillLoads()).singleElement().satisfies(load -> {
                assertThat(load.skillId()).isEqualTo(skillId(request.scope));
                assertThat(load.state()).isEqualTo(request.body == null ? "ERROR" : "SUCCESS");
                assertThat(load.instructionsLoaded()).isEqualTo(request.body != null);
                if (request.body != null) assertThat(load.output()).contains(request.body);
                else assertThat(load.output()).doesNotContain(A_BODY, A_NEW_BODY, B_BODY, OTHER_BODY);
            });
        }

        Path onlyDirectory() {
            assertThat(directories.paths).hasSize(1);
            return directories.paths.element();
        }

        void assertCapturedDirectoriesRemoved(int expectedCount) {
            assertThat(directories.paths).hasSize(expectedCount).doesNotHaveDuplicates();
            assertThat(directories.paths).allSatisfy(path -> assertThat(Files.exists(path)).isFalse());
        }

        @Override
        public void close() throws InterruptedException {
            try {
                requests.values().forEach(Request::release);
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                skillLogger.detachAppender(directories);
                directories.stop();
            }
        }
    }
}
