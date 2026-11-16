package com.laoshiren.agent.springboot.controller;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ReActAgent reActAgent;

    @GetMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam("message") String message,
            @RequestParam(name = "sessionId", defaultValue = "web-demo") String sessionId) {

        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId(sessionId)
                .build();

        // 每次请求只创建执行流，共享 Agent 由 Spring 在容器关闭时释放。
        return reActAgent.streamEvents(message, context)
                .subscribeOn(Schedulers.boundedElastic())
                .ofType(TextBlockDeltaEvent.class)
                .map(TextBlockDeltaEvent::getDelta);
    }

}
