package com.laoshiren.agent.skills.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.skill.AgentSkill;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 直接驱动真实事件，覆盖流式拼接、异常终态、快照隔离和日志关联。 */
class SkillCallTraceTest {
    private static final String LOAD_TOOL = "load_skill_through_path";
    private static final String BODY = "PRIVATE_INSTRUCTIONS：检查租户与用户范围。";
    private static final AgentSkill SKILL = new AgentSkill("review", "检查代码归属", BODY, Map.of());

    @Test
    void agentMiddlewareObservesEventsOnceAndOnlyAfterSubscription() {
        var trace = new SkillCallTrace("call-events");
        var input = new AgentInput(List.of());
        var delegated = new AtomicInteger();
        List<AgentEvent> events = List.of(new ModelCallStartEvent("reply"), answer("最终回答"));
        var stream = trace.onAgent(null, null, input, actual -> {
            assertThat(actual).isSameAs(input);
            delegated.incrementAndGet();
            return Flux.fromIterable(events);
        });

        assertThat(delegated.get()).isZero();
        assertThat(trace.hasAnswer()).isFalse();
        assertThat(stream.collectList().block()).containsExactlyElementsOf(events);
        var result = trace.result(null);
        assertThat(delegated.get()).isEqualTo(1);
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.answer()).isEqualTo("最终回答");
        assertThat(result.steps()).extracting(step -> step.type()).containsExactly("MODEL_CALL", "ANSWER");
    }

    @Test
    void actingRegistersOnlySkillCallsAndDefersUntilSubscription() {
        var trace = trace();
        var input = new ActingInput(List.of(tool("load-1", SKILL.getSkillId(), "SKILL.md"),
                ToolUseBlock.builder().id("other-1").name("other_tool").input(Map.of()).build()));
        var stream = trace.onActing(null, null, input, actual -> {
            assertThat(actual).isSameAs(input);
            return Flux.empty();
        });

        assertThat(trace.result(null).skillLoads()).isEmpty();
        stream.blockLast();
        assertThat(trace.result(null).skillLoads()).singleElement().satisfies(load ->
                assertThat(load.toolCallId()).isEqualTo("load-1"));
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = ToolResultState.class, names = "RUNNING")
    void invalidEndKeepsCorrelationForLaterFragmentsAndValidEnd(ToolResultState invalidState) {
        var trace = trace();
        request(trace, "load-1", SKILL.getSkillId(), "SKILL.md");
        trace.record(delta("load-1", BODY.substring(0, 10)));
        trace.record(end("load-1", invalidState));

        var partial = trace.result("TIMED_OUT").skillLoads().get(0);
        assertThat(partial.state()).isEqualTo("UNKNOWN");
        assertThat(partial.terminalStateObserved()).isFalse();
        assertThat(partial.timing().endedAt()).isNull();
        assertThat(partial.observationEndReason()).isEqualTo("TIMED_OUT");

        trace.record(delta("load-1", BODY.substring(10)));
        trace.record(end("load-1", ToolResultState.SUCCESS));

        var result = trace.result(null);
        assertThat(result.skillLoads()).singleElement().satisfies(load -> {
            assertThat(load.state()).isEqualTo("SUCCESS");
            assertThat(load.output()).isEqualTo(BODY);
            assertThat(load.instructionsLoaded()).isTrue();
            assertThat(load.terminalStateObserved()).isTrue();
            assertThat(load.observationEndReason()).isNull();
            assertThat(load.timing().endedAt()).isNotNull();
        });
        assertThat(result.steps()).extracting(step -> step.type()).containsExactly(
                "SKILLS_DISCOVERED", "SKILL_LOAD_REQUESTED", "SKILL_LOAD_FINISHED");
        // 之后收到的事件不能回写先前已经返回的快照。
        assertThat(partial.output()).isEqualTo(BODY.substring(0, 10));
        assertThat(partial.state()).isEqualTo("UNKNOWN");
    }

    @Test
    void duplicateEndAndLateOutputCannotRewriteCompletedAttempt() {
        var trace = trace();
        request(trace, "load-1", SKILL.getSkillId(), "SKILL.md");
        trace.record(delta("load-1", BODY));
        trace.record(end("load-1", ToolResultState.SUCCESS));
        var before = trace.result(null);

        trace.record(end("load-1", ToolResultState.ERROR));
        trace.record(delta("load-1", "late-output"));
        trace.record(end("unrelated-call", ToolResultState.SUCCESS));

        var after = trace.result(null);
        assertThat(after.skillLoads()).isEqualTo(before.skillLoads());
        assertThat(after.steps()).isEqualTo(before.steps());
    }

    @Test
    void cancellationKeepsUnfinishedLoadUnknownAndTimingWithinCallSnapshot() {
        var trace = trace();
        request(trace, "load-1", SKILL.getSkillId(), "SKILL.md");
        trace.record(delta("load-1", BODY));
        var subscription = trace.onAgent(null, null, new AgentInput(List.of()), ignored -> Flux.never()).subscribe();
        subscription.dispose();

        var result = trace.result("TIMED_OUT");
        assertThat(result.skillLoads()).singleElement().satisfies(load -> {
            assertThat(load.state()).isEqualTo("UNKNOWN");
            assertThat(load.instructionsLoaded()).isFalse();
            assertThat(load.terminalStateObserved()).isFalse();
            assertThat(load.observationEndReason()).isEqualTo("TIMED_OUT");
            assertThat(load.output()).isEqualTo(BODY);
            assertThat(load.timing().endedAt()).isNull();
            assertThat(load.timing().elapsedMs()).isBetween(0L, result.timing().elapsedMs());
        });
    }

    @ParameterizedTest
    @EnumSource(value = ToolResultState.class, mode = EnumSource.Mode.EXCLUDE, names = {"SUCCESS", "RUNNING"})
    void nonSuccessTerminalStatesNeverClaimLoadedInstructions(ToolResultState state) {
        var trace = trace();
        request(trace, "load-1", SKILL.getSkillId(), "SKILL.md");
        trace.record(delta("load-1", BODY));
        trace.record(end("load-1", state));

        assertThat(trace.result(null).skillLoads()).singleElement().satisfies(load -> {
            assertThat(load.state()).isEqualTo(state.name());
            assertThat(load.terminalStateObserved()).isTrue();
            assertThat(load.instructionsLoaded()).isFalse();
            assertThat(load.observationEndReason()).isNull();
            assertThat(load.timing().endedAt()).isNotNull();
        });
    }

    @Test
    void successRequiresKnownSkillExactPathAndCompleteBody() {
        var trace = trace();
        request(trace, "unknown", "other-user-skill", "SKILL.md");
        request(trace, "resource", SKILL.getSkillId(), "reference.md");
        request(trace, "partial", SKILL.getSkillId(), "SKILL.md");
        for (String id : List.of("unknown", "resource", "partial")) {
            trace.record(delta(id, "partial".equals(id) ? BODY.substring(0, 10) : BODY));
            trace.record(end(id, ToolResultState.SUCCESS));
        }

        var result = trace.result(null);
        assertThat(result.skillLoads()).allSatisfy(load -> assertThat(load.instructionsLoaded()).isFalse());
        assertThat(result.skillLoads().get(0).skillName()).isNull();
    }

    @Test
    void concurrentLoadsKeepOutputAndStepSequencesSeparate() {
        var trace = trace();
        request(trace, "load-a", SKILL.getSkillId(), "SKILL.md");
        request(trace, "load-b", SKILL.getSkillId(), "SKILL.md");

        Flux.fromIterable(List.of("load-a", "load-b"))
                .flatMap(id -> Mono.fromRunnable(() -> {
                    trace.record(delta(id, id + ":"));
                    for (int i = 0; i < BODY.length(); i++) {
                        trace.record(delta(id, BODY.substring(i, i + 1)));
                    }
                    trace.record(end(id, ToolResultState.SUCCESS));
                }).subscribeOn(Schedulers.parallel()))
                .blockLast(Duration.ofSeconds(5));

        var result = trace.result(null);
        assertThat(result.skillLoads()).hasSize(2).allSatisfy(load -> {
            assertThat(load.output()).isEqualTo(load.toolCallId() + ":" + BODY);
            assertThat(load.instructionsLoaded()).isTrue();
        });
        assertThat(result.steps()).extracting(step -> step.sequence()).containsExactly(1, 2, 3, 4, 5);
        assertThat(result.steps()).extracting(step -> step.elapsedMs()).isSorted();
    }

    @Test
    void logsCorrelateDiscoveryLoadsAndResultWithoutIncludingContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(SkillCallTrace.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var trace = trace();
            request(trace, "load-1", SKILL.getSkillId(), "SKILL.md");
            trace.record(delta("load-1", BODY));
            trace.record(end("load-1", ToolResultState.SUCCESS));
            trace.record(answer("PRIVATE_ANSWER"));
            trace.result(null);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(line -> assertThat(line).contains("callId=call-test", "skillCount=1"))
                    .anySatisfy(line -> assertThat(line).contains("toolCallId=load-1", "skillId=" + SKILL.getSkillId()))
                    .anySatisfy(line -> assertThat(line).contains("state=SUCCESS", "instructionsLoaded=true"))
                    .anySatisfy(line -> assertThat(line).contains("status=COMPLETED", "elapsedMs="))
                    .allSatisfy(line -> assertThat(line).doesNotContain(BODY, "PRIVATE_ANSWER"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void loggingSanitizesUntrustedParametersAndPreservesOriginalResults() {
        Logger logger = (Logger) LoggerFactory.getLogger(SkillCallTrace.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var trace = trace();
            String path = "bad\r\npath\u2028" + "x".repeat(200);
            request(trace, "load\n1", SKILL.getSkillId(), path);
            assertThat(trace.result("FAILED").skillLoads().get(0).path()).isEqualTo(path);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(line -> assertThat(line).contains("toolCallId=load_1", "path=bad__path_", "..."))
                    .allSatisfy(line -> assertThat(line).doesNotContain("\r", "\n", "\u2028", "x".repeat(200)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void middlewarePropagatesOriginalErrorWithoutLoggingProviderMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(SkillCallTrace.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var trace = trace();
            var error = new IllegalStateException("PRIVATE_PROVIDER_MESSAGE");
            var stream = trace.onAgent(null, null, new AgentInput(List.of()), ignored -> { throw error; });
            assertThatThrownBy(stream::blockLast).isSameAs(error);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(line -> assertThat(line).contains("callId=call-test", "errorType=IllegalStateException"))
                    .allSatisfy(line -> assertThat(line).doesNotContain("PRIVATE_PROVIDER_MESSAGE"));
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static SkillCallTrace trace() {
        var trace = new SkillCallTrace("call-test");
        trace.discover(List.of(SKILL));
        return trace;
    }

    private static void request(SkillCallTrace trace, String callId, String skillId, String path) {
        trace.onActing(null, null, new ActingInput(List.of(tool(callId, skillId, path))), ignored -> Flux.empty())
                .blockLast();
    }

    private static ToolUseBlock tool(String callId, String skillId, String path) {
        return ToolUseBlock.builder().id(callId).name(LOAD_TOOL)
                .input(Map.of("skillId", skillId, "path", path)).build();
    }

    private static ToolResultTextDeltaEvent delta(String callId, String text) {
        return new ToolResultTextDeltaEvent("reply", callId, LOAD_TOOL, text);
    }

    private static ToolResultEndEvent end(String callId, ToolResultState state) {
        return new ToolResultEndEvent("reply", callId, LOAD_TOOL, state);
    }

    private static AgentResultEvent answer(String text) {
        return new AgentResultEvent(Msg.builder().content(TextBlock.builder().text(text).build()).build());
    }
}
