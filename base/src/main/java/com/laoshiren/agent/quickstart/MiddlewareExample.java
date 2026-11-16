package com.laoshiren.agent.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import reactor.core.publisher.Flux;

import java.util.function.Function;

public class MiddlewareExample extends BaseExample {

    public static void main(String[] args) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("middleware-demo")
                .build();

        try (ReActAgent agent = reActAgent(openAIChatModel(), toolkit(), agentStateStore(), modelTimingMiddleware())) {

            chat(agent, context, "帮我查询订单 A001 的状态。");
        }

    }


    public static MiddlewareBase modelTimingMiddleware() {
        return new ModelTimingMiddleware();
    }

    public static class ModelTimingMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext context,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                long start = System.nanoTime();

                System.out.printf("[模型调用开始] agent=%s，session=%s%n", agent.getName(), context.getSessionId());

                return next.apply(input)
                        .doOnError(error -> System.err.println("[模型调用异常] " + error.getMessage()))
                        .doFinally(signal -> {
                            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                            System.out.printf("[模型调用结束] 状态=%s，耗时=%d ms%n", signal, elapsedMillis);
                        });
            });
        }
    }

}
