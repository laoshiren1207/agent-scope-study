package com.laoshiren.agent.persistent.service;

import com.laoshiren.agent.persistent.api.ChunkRequests.Search;
import com.laoshiren.agent.persistent.api.ChunkResponses;
import com.laoshiren.agent.persistent.api.ChunkResponses.Answer;
import com.laoshiren.agent.persistent.config.RagProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 基于持久化片段检索结果生成回答，并返回本次回答使用的候选参考资料。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnswerService {
    /** 负责查询向量生成以及当前租户、知识库和 profile 内的片段检索。 */
    private final KbChunkService chunks;
    /** 用于生成自然语言回答的聊天模型，与配置中的 Agent 使用相同模型。 */
    private final OpenAIChatModel chatModel;
    /** 提供聊天模型调用的超时时间。 */
    private final RagProperties properties;

    private final ReActAgent reActAgent;


    /**
     * 检索参考片段，再将片段正文和问题交给已配置的 Agent 生成回答。
     *
     * <p>没有命中资料时直接返回固定说明，不调用聊天模型；有命中时在提示词中使用
     * {@code [chunk:行ID]} 标记资料，并要求模型依据资料作答和引用。
     * 返回的 references 为本次召回列表，不表示其中每项都被模型实际引用。
     *
     * @param request 已完成校验的查询范围、问题及检索参数
     * @return 回答文本以及本次召回的参考片段
     * @throws ModelCallException 问题向量化失败，或聊天调用及结果校验失败
     */
    public Answer ask(Search request) {
        return answer(request, chunks.search(request));
    }

    /**
     * 根据调用方已选好的参考片段生成回答，供普通检索和重排问答复用。
     * 此方法不执行检索；片段的顺序、数量及范围由上游服务确定。
     */
    public Answer answer(Search request, List<ChunkResponses.Hit> hits) {
        // 1. 没有参考资料时直接结束，避免无依据地调用聊天模型。
        if (hits.isEmpty()) {
            return new Answer("现有资料未说明。", hits);
        }
        // 2. 使用数据库行主键作为引用标识，将检索到的正文组织为参考资料。
        String references = hits.stream()
                .map(hit -> "[chunk:%d]\n%s".formatted(hit.id(), hit.content()))
                .collect(Collectors.joining("\n\n"));
        // 3. 使用已配置的 Agent，为本次问答创建调用上下文。
        try {
            // 租户标识与随机会话 ID 用于组织本次调用上下文；每次请求使用不同会话。
            RuntimeContext context = RuntimeContext.builder()
                    .userId("tenant:" + request.tenantId())
                    .sessionId(UUID.randomUUID().toString())
                    .build();
            // 4. 发送参考资料与问题，最多等待配置的秒数，再校验模型是否返回有效文本。
            Msg reply = reActAgent.call("参考资料：\n%s\n\n用户问题：\n%s"
                            .formatted(references, request.query()), context)
                    .block(Duration.ofSeconds(properties.getChat().getTimeoutSeconds()));
            if (reply == null || reply.getTextContent() == null || reply.getTextContent().isBlank()) {
                throw new IllegalStateException("聊天模型未返回文本");
            }
            return new Answer(reply.getTextContent(), hits);
        } catch (RuntimeException exception) {
            // 对调用方返回统一说明，同时保留原始异常作为 cause，便于排查。
            throw new ModelCallException("问答模型调用失败或超时", exception);
        }
    }
}
