package com.laoshiren.agent.persistent.service;

import com.laoshiren.agent.persistent.api.ChunkRequests;
import com.laoshiren.agent.persistent.api.ChunkRequests.Ingest;
import com.laoshiren.agent.persistent.api.ChunkRequests.Search;
import com.laoshiren.agent.persistent.api.ChunkRequests.VersionScope;
import com.laoshiren.agent.persistent.api.ChunkResponses.Hit;
import com.laoshiren.agent.persistent.api.ChunkResponses.Ingested;
import com.laoshiren.agent.persistent.config.RagProperties;
import com.laoshiren.agent.persistent.entity.KbChunk;
import com.laoshiren.agent.persistent.mapper.KbChunkMapper;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识库片段服务，负责登记片段、生成向量、检索以及文档版本的归档和逻辑删除。
 *
 * <p>入库与检索使用服务端当前配置的 embedding profile，避免混用不同生成规则的向量。
 */
@Service
public class KbChunkService {
    /** 片段持久化及 pgvector 检索入口。 */
    private final KbChunkMapper mapper;
    /** 将片段正文或查询文本转换为向量的 AgentScope 模型。 */
    private final EmbeddingModel embeddingModel;
    /** 提供向量模型名称、profile、维度及调用超时时间的服务端配置。 */
    private final RagProperties properties;
    /** 用于原子登记本次请求中所有 pending 片段的短事务。 */
    private final TransactionTemplate transaction;

    /**
     * 注入持久化、模型和配置依赖，并创建片段登记所需的事务模板。
     *
     * @param mapper 片段数据访问接口
     * @param embeddingModel 向量模型
     * @param properties RAG 配置
     * @param transactionManager Spring 管理的数据库事务管理器
     */
    public KbChunkService(KbChunkMapper mapper, EmbeddingModel embeddingModel,
                          RagProperties properties, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 接收已分块的文档内容，登记为 pending 后逐条生成向量并发布。
     *
     * <p>本次请求中的片段在同一事务内登记，提交后才调用模型，避免模型请求长期占用数据库事务。
     * 向量化失败的片段记录为 failed 并继续处理后续片段；成功片段立即变为 ready，
     * 因此整个文档版本可能暂时或最终处于部分成功状态。
     *
     * @param request 已完成参数校验的文档归属、版本、来源及有序片段列表
     * @return 按输入顺序排列的片段处理结果，包含行主键、片段序号、状态和失败说明
     * @throws org.springframework.dao.DuplicateKeyException 片段唯一键冲突，登记事务整体回滚
     * @throws IllegalStateException 处理期间片段已归档、删除或不再满足状态更新条件
     */
    public List<Ingested> ingest(Ingest request) {
        // 1. 按列表位置生成从 0 开始的片段序号，保留原始正文并计算内容指纹。
        List<KbChunk> chunks = new ArrayList<>();
        for (int i = 0; i < request.chunks().size(); i++) {
            ChunkRequests.Chunk input = request.chunks().get(i);
            KbChunk chunk = new KbChunk();
            // 基础信息
            chunk.setTenantId(request.tenantId());
            chunk.setKnowledgeBaseId(request.knowledgeBaseId());
            chunk.setDocumentId(request.documentId());
            chunk.setDocumentVersion(request.documentVersion());
            // 片段
            chunk.setChunkNo(i);
            chunk.setTitle(input.title());
            chunk.setContent(input.content());
            chunk.setContentHash(sha256(input.content()));
            chunk.setTokenCount(input.tokenCount());
            chunk.setSourceUri(request.sourceUri());
            chunk.setSourceLocator(input.sourceLocator() == null ? Map.of() : input.sourceLocator());
            chunk.setMetadata(input.metadata() == null ? Map.of() : input.metadata());
            // profile 和模型名统一由服务端配置决定，随每个片段一同持久化。
            chunk.setEmbeddingProfile(properties.getEmbedding().getProfile());
            chunk.setEmbeddingModel(properties.getEmbedding().getModel());
            chunk.setStatus("pending");
            chunks.add(chunk);
        }
        // 2. 原子登记本次全部片段；唯一约束冲突会整体回滚，不覆盖已有内容或向量。
        transaction.executeWithoutResult(status -> chunks.forEach(mapper::insert));

        // 3. 登记事务已经提交，逐条调用模型并独立更新每个片段的处理状态。
        List<Ingested> results = new ArrayList<>();
        for (KbChunk chunk : chunks) {
            float[] vector;
            try {
                vector = this.embed(chunk.getContent());
            } catch (ModelCallException exception) {
                // 单个片段向量化失败不终止整个批次，但失败状态必须成功写入数据库。
                String error = exception.getMessage();
                requireTransition(mapper.markFailed(chunk.getId(), error));
                results.add(new Ingested(chunk.getId(), chunk.getChunkNo(), "failed", error));
                continue;
            }
            // 数据库写入失败直接向调用方报错，不能伪装成向量化失败。
            requireTransition(mapper.markReady(chunk.getId(), vector));
            results.add(new Ingested(chunk.getId(), chunk.getChunkNo(), "ready", null));
        }
        return List.copyOf(results);
    }

    /**
     * 使用当前向量配置生成问题向量，并在指定租户和知识库内检索可见片段。
     *
     * @param request 已完成校验的查询范围、问题、返回数量和最低相似度
     * @return 按相似度降序排列的匹配片段；没有满足条件的片段时返回空列表
     * @throws ModelCallException 查询文本向量化失败或向量不符合配置
     */
    public List<Hit> search(Search request) {
        // 1. 将用户的问题转换为查询向量，复用入库时的模型及向量校验逻辑。
        float[] queryVector = embed(request.query());

        // 2. 使用服务端当前 profile，确保只比较同一套生成规则下的问题向量和片段向量。
        String profile = properties.getEmbedding().getProfile();
        int topK = request.limit();
        double minScore = request.threshold();

        // 3. 将范围、向量和筛选条件交给 Mapper，由 PostgreSQL 计算相似度并返回原文片段。
        List<Hit> hits = mapper.search(request.tenantId(), request.knowledgeBaseId(), profile, queryVector, minScore, topK);
        return hits;
    }

    /**
     * 归档指定文档版本在当前 profile 下的未删除片段，使其退出检索。
     *
     * @param scope 已完成校验的租户、知识库、文档和版本范围
     * @return 本次归档的片段行数
     */
    public int archive(VersionScope scope) {
        return mapper.archiveVersion(scope, properties.getEmbedding().getProfile());
    }

    /**
     * 逻辑删除指定文档版本在当前 profile 下的片段，保留记录及原唯一键。
     *
     * @param scope 已完成校验的租户、知识库、文档和版本范围
     * @return 本次逻辑删除的片段行数
     */
    public int delete(VersionScope scope) {
        return mapper.deleteVersion(scope, properties.getEmbedding().getProfile());
    }

    /**
     * 在配置的超时时间内获取文本向量，并转换为 pgvector 使用的单精度数组。
     *
     * <p>校验返回维度、转换后的浮点数有限性和非零范数；模型调用或校验失败统一包装为模型异常。
     *
     * @param text 用于向量化的原始正文或查询文本
     * @return 符合当前维度配置、元素有限且非零的单精度向量
     * @throws ModelCallException 模型请求失败、超时、返回为空或向量校验失败
     */
    private float[] embed(String text) {
        try {
            double[] response = embeddingModel.embed(TextBlock.builder().text(text).build())
                    .block(Duration.ofSeconds(properties.getEmbedding().getTimeoutSeconds()));
            if (response == null || response.length != properties.getEmbedding().getDimensions()) {
                throw new IllegalStateException("模型返回的向量维度与配置不一致");
            }
            float[] vector = new float[response.length];
            // 在转为 float 后检查，既识别溢出，也拒绝所有元素下溢为零的情况。
            double norm = 0;
            for (int i = 0; i < response.length; i++) {
                vector[i] = (float) response[i];
                if (!Float.isFinite(vector[i])) {
                    throw new IllegalStateException("模型返回的向量包含非有限值");
                }
                norm += (double) vector[i] * vector[i];
            }
            if (norm == 0) throw new IllegalStateException("模型返回了零向量");
            return vector;
        } catch (RuntimeException exception) {
            // 不向 API 或 error_message 泄露供应商异常中的 URL、凭证或响应正文。
            throw new ModelCallException("向量化失败：模型调用超时、不可用或返回的向量不符合配置", exception);
        }
    }

    /**
     * 确认条件更新恰好命中一个片段，避免把并发归档或删除后的记录误报为处理成功。
     *
     * @param affectedRows 数据库状态更新影响的行数
     * @throws IllegalStateException 更新未恰好命中一行
     */
    private static void requireTransition(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("片段状态已变更，可能已归档或删除，请重新查询");
        }
    }

    /**
     * 对未经裁剪或预处理的原始正文按 UTF-8 编码计算内容指纹。
     *
     * @param content 原始片段正文
     * @return 小写 64 位十六进制 SHA-256 字符串
     * @throws IllegalStateException 运行环境不支持 SHA-256
     */
    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
