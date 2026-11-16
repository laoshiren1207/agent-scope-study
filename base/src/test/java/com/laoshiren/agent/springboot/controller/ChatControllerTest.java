package com.laoshiren.agent.springboot.controller;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ReActAgent agent = mock(ReActAgent.class);
    private final ChatController controller = new ChatController(agent);

    @Test
    void completedRequestsReuseAgentWithTheirOwnSessionContext() {
        when(agent.streamEvents(anyString(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new TextBlockDeltaEvent("reply", "block", "你好")));

        assertEquals(List.of("你好"), controller.stream("你好", "session-a").collectList().block(TIMEOUT));
        assertEquals(List.of("你好"), controller.stream("你好", "session-b").collectList().block(TIMEOUT));

        ArgumentCaptor<RuntimeContext> contexts = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent, times(2)).streamEvents(anyString(), contexts.capture());
        assertEquals("alice", contexts.getAllValues().get(0).getUserId());
        assertEquals("session-a", contexts.getAllValues().get(0).getSessionId());
        assertEquals("session-b", contexts.getAllValues().get(1).getSessionId());
        verify(agent, never()).close();
    }

    @Test
    void failedRequestPropagatesErrorWithoutClosingAgent() {
        IllegalStateException failure = new IllegalStateException("模型调用失败");
        when(agent.streamEvents(anyString(), any(RuntimeContext.class)))
                .thenReturn(Flux.error(failure));

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> controller.stream("你好", "session-a").blockLast(TIMEOUT));

        assertSame(failure, actual);
        verify(agent, never()).close();
    }

    @Test
    void cancelledRequestCancelsItsStreamWithoutClosingAgent() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<AgentEvent> events = Flux.<AgentEvent>just(
                        new TextBlockDeltaEvent("reply", "block", "你好"))
                .concatWith(Flux.never())
                .doOnCancel(() -> cancelled.set(true));
        when(agent.streamEvents(anyString(), any(RuntimeContext.class))).thenReturn(events);

        assertEquals("你好", controller.stream("你好", "session-a").next().block(TIMEOUT));

        assertTrue(cancelled.get());
        verify(agent, never()).close();
    }
}
