package com.laoshiren.agent.persistent.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.laoshiren.agent.persistent.typehandler.JsonbTypeHandler;
import com.laoshiren.agent.persistent.typehandler.VectorTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "public.kb_chunk", autoResultMap = true)
public class KbChunk {
    /** 片段行主键，由数据库自增生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属租户的业务 ID；单租户系统可使用固定值。 */
    private Long tenantId;

    /** 所属知识库的业务 ID，用于限定检索范围。 */
    private Long knowledgeBaseId;

    /** 来源文档的稳定业务 ID，文档更新后保持不变。 */
    private Long documentId;

    /** 文档入库修订号，从 1 开始；原文、解析方式或分块规则变化时递增。 */
    private Integer documentVersion;

    /** 当前文档入库版本内的片段序号，从 0 开始，可用于查找相邻片段。 */
    private Integer chunkNo;

    /** 片段标题或所属章节标题，可为空。 */
    private String title;

    /** 用于向量化和提供给大模型的片段正文，不能为空白，建议保留必要的章节标题。 */
    private String content;

    /** 正文按 UTF-8 编码计算的 SHA-256，由应用生成，为小写 64 位十六进制字符串。 */
    private String contentHash;

    /** 使用对应模型分词器统计的片段 Token 数量，必须大于 0，未统计时为空。 */
    private Integer tokenCount;

    /** 来源文档地址，例如本地文件路径、对象存储地址或网页 URL，可为空。 */
    private String sourceUri;

    /** 原文定位信息，例如章节路径、页码和起止行号；保存为 JSONB 对象，默认值为 {}。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> sourceLocator;

    /** 业务扩展属性，例如标签、业务分类和语言；保存为 JSONB 对象，默认值为 {}。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> metadata;

    /** 不可变的向量配置标识，区分实际模型版本、维度及预处理规则；配置变化时使用新标识。 */
    private String embeddingProfile;

    /** 生成向量时使用的模型名称，例如模型服务对外暴露的名称 Qwen3.5。 */
    private String embeddingModel;

    /** 片段的单精度浮点向量，默认约定为 4096 维，处理中可为空；查询向量须使用相同配置。 */
    @TableField(typeHandler = VectorTypeHandler.class)
    private float[] embedding;

    /** 当前片段成功生成向量的时间；ready 状态下必须有值。 */
    private OffsetDateTime embeddedAt;

    /** 片段状态：pending 处理中、ready 可检索、failed 处理失败、archived 历史版本已归档。 */
    private String status;

    /** 最近一次解析、向量化或建库失败的原因，成功后可清空。 */
    private String errorMessage;

    /** 片段行创建时间，插入时由数据库默认值生成，应用不直接写入。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    /** 片段行最后更新时间，插入时使用数据库默认值，后续更新由数据库触发器维护。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime updatedAt;

    /** 逻辑删除时间；为空表示未删除，非空表示已删除并退出检索。 */
    private OffsetDateTime deletedAt;
}
