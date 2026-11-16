package com.laoshiren.agent.persistent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoshiren.agent.persistent.api.ChunkRequests.VersionScope;
import com.laoshiren.agent.persistent.api.ChunkResponses.Hit;
import com.laoshiren.agent.persistent.entity.KbChunk;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 知识库片段的数据访问接口。
 *
 * <p>继承 MyBatis-Plus 的基础增删改查能力：上传入库时调用继承的 insert(KbChunk)，
 * 由实体映射生成 INSERT SQL，插入 pending 行并回填数据库自增主键。
 * 自定义方法的 SQL 定义在 KbChunkMapper.xml 中；向量生成后调用 markReady 写入 embedding。
 * 自定义检索、归档和逻辑删除 SQL 显式限定业务范围及可见状态。
 */
public interface KbChunkMapper extends BaseMapper<KbChunk> {
    /**
     * 保存生成的向量，将仍为 pending 且未删除的片段发布为 ready。
     * 同时记录向量生成时间并清空错误信息，避免覆盖已归档或已删除的片段。
     *
     * @param id 已由服务端登记的片段行主键
     * @param embedding 与片段向量配置一致的单精度向量
     * @return 更新行数；成功为 1，状态不再满足更新条件时为 0
     */
    int markReady(@Param("id") long id, @Param("embedding") float[] embedding);

    /**
     * 将仍为 pending 且未删除的片段标记为 failed，并记录本次失败原因。
     *
     * @param id 已由服务端登记的片段行主键
     * @param error 可对外展示和持久化的失败说明
     * @return 更新行数；成功为 1，状态不再满足更新条件时为 0
     */
    int markFailed(@Param("id") long id, @Param("error") String error);

    /**
     * 在指定租户、知识库和向量配置内执行精确余弦检索。
     *
     * <p>仅检索 ready、未删除且向量非空的片段；相似度为 1 减去余弦距离，
     * 按相似度降序排列，同分时按行主键升序排列。
     * 方法对应 XML 中的 select id="search"，通过 @Param 名称绑定 SQL 参数。
     * VectorTypeHandler 将问题的 float[] 向量转换为 PostgreSQL vector 参数。
     *
     * @param tenantId 所属租户业务 ID
     * @param knowledgeBaseId 目标知识库业务 ID
     * @param profile 查询与已存片段共同使用的向量配置标识
     * @param embedding 查询文本的向量，生成规则及维度须与片段向量一致
     * @param minScore 最低余弦相似度，包含该边界值，取值范围为 -1 到 1
     * @param limit 最多返回的片段数量
     * @return 满足范围和相似度条件的片段，包含正文、来源、扩展属性及相似度分数
     */
    List<Hit> search(@Param("tenantId") long tenantId,
                     @Param("knowledgeBaseId") long knowledgeBaseId,
                     @Param("profile") String profile,
                     @Param("embedding") float[] embedding,
                     @Param("minScore") double minScore,
                     @Param("limit") int limit);

    /**
     * 将指定文档版本、指定向量配置下未删除且尚未归档的片段标记为 archived，退出检索。
     *
     * @param scope 包含租户、知识库、文档及版本号的业务范围
     * @param profile 本次操作限定的向量配置标识
     * @return 本次归档的片段行数；没有匹配记录时为 0
     */
    int archiveVersion(@Param("scope") VersionScope scope, @Param("profile") String profile);

    /**
     * 设置指定文档版本、指定向量配置下片段的 deleted_at，实现逻辑删除。
     * 保留原始记录及唯一键，重复调用不会再次更新已经删除的记录。
     *
     * @param scope 包含租户、知识库、文档及版本号的业务范围
     * @param profile 本次操作限定的向量配置标识
     * @return 本次逻辑删除的片段行数；没有匹配记录时为 0
     */
    int deleteVersion(@Param("scope") VersionScope scope, @Param("profile") String profile);
}
