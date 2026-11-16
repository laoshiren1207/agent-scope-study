package com.laoshiren.agent.persistent.service;

import com.laoshiren.agent.persistent.api.ChunkRequests.Search;
import com.laoshiren.agent.persistent.api.ChunkResponses.Answer;
import com.laoshiren.agent.persistent.api.ChunkResponses.Hit;
import com.laoshiren.agent.persistent.api.ChunkResponses.RerankAnswer;
import com.laoshiren.agent.persistent.api.ChunkResponses.RerankHit;
import com.laoshiren.agent.persistent.config.RagProperties;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 组织问题向量化、数据库召回、TEI 重排、分数过滤以及最终回答。 */
@Service
@RequiredArgsConstructor
public class RagRerankAnswerService {
    /** 复用现有查询链路，内部已经包含问题的 Embedding 和 Mapper 查询。 */
    private final KbChunkService chunks;
    /** 使用 TEI 对原始问题和候选片段正文重新评分。 */
    private final TeiReranker reranker;
    /** 复用参考资料拼装、Agent 调用及回答校验逻辑。 */
    private final RagAnswerService answers;
    /** 重排开关、片段数量及最低分数来自 rag.rerank 配置。 */
    private final RagProperties properties;

    /**
     * 检索后按配置选择是否重排并过滤，将最终保留的片段交给回答模型。
     *
     * @param request topK/minScore 用于向量召回；重排参数使用独立配置
     * @return 回答和最终参考片段，同时保留向量相似度及重排分数
     * @throws ModelCallException 向量、重排或回答模型调用失败
     */
    public RerankAnswer ask(Search request) {
        // 1. 问题 → 查询向量 → pgvector 候选片段；候选为空时跳过重排和回答模型。
        List<Hit> candidates = chunks.search(request);
        if (candidates.isEmpty()) {
            return new RerankAnswer("现有资料未说明。", List.of());
        }

        // 关闭重排时保留向量召回的顺序和数量，不应用重排的 topK/minScore。
        // 返回结构保持一致；null 表示未计算重排分数，向量分数仍在 hit.score 中。
        if (!properties.getRerank().isEnable()) {
            Answer answer = answers.answer(request, candidates);
            List<RerankHit> references = candidates.stream()
                    .map(hit -> new RerankHit(hit, null)).toList();
            return new RerankAnswer(answer.answer(), references);
        }

        // 2. 按 TEI 分数重排，最多保留配置中的 topK 条。
        List<RerankHit> reranked;
        try {
            reranked = reranker.rerank(request.query(), candidates, properties.getRerank().getTopK());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("重排模型调用被中断", exception);
        } catch (IOException exception) {
            throw new ModelCallException("重排模型调用失败、超时或返回结果不符合预期", exception);
        }

        // 3. 使用独立的重排阈值过滤；全部被过滤时直接返回资料不足。
        List<RerankHit> references = reranker.minScore(reranked, properties.getRerank().getMinScore());
        if (references.isEmpty()) {
            return new RerankAnswer("现有资料未说明。", List.of());
        }

        // 4. 只传入重排并过滤后的原文，复用回答逻辑，不再次执行检索或 Embedding。
        List<Hit> selectedHits = references.stream().map(RerankHit::hit).toList();
        Answer answer = answers.answer(request, selectedHits);
        return new RerankAnswer(answer.answer(), references);
    }
}
