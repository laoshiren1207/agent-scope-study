package com.laoshiren.agent.skills.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.typehandler.JsonbTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

/** 个人技能定义，对应 public.personal_skill。 */
@Data
@TableName(value = "public.personal_skill", autoResultMap = true)
public class PersonalSkill {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属租户，创建后不可通过实体更新修改。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;

    /** 所属用户，创建后不可通过实体更新修改。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long ownerUserId;

    private String name;

    private String description;

    /** SKILL.md 正文，不包含 YAML 头部。 */
    private String instructionsMd;

    /** YAML 头部的其他属性，不包含 name 和 description。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> extraFrontmatter;

    private PersonalSkillStatus status;

    /** 更新时由 Service 校验旧值并通过 SQL 递增。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long revision;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    /** 插入时使用数据库默认值，更新时由 Service 的 SQL 设置。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime updatedAt;
}
