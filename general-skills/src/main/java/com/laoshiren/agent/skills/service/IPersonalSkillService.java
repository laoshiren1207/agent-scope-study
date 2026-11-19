package com.laoshiren.agent.skills.service;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.param.CreateSkillDraft;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.UpdateSkill;
import com.laoshiren.agent.skills.result.PersonalSkillPage;

import java.util.List;
import java.util.Optional;

/** 个人技能业务入口，所有操作都必须传入当前调用者的数据范围。 */
public interface IPersonalSkillService {
    /**
     * 创建当前用户的草稿，返回包含数据库主键和时间戳的完整记录。
     *
     * @throws jakarta.validation.ConstraintViolationException 技能内容不合法
     * @throws IllegalArgumentException 缺少范围、创建参数或扩展头部包含保留字段
     * @throws org.springframework.dao.DuplicateKeyException 当前租户、用户下已有同名技能
     */
    PersonalSkill createDraft(SkillScope scope, CreateSkillDraft draft);

    /** 查询当前租户、用户拥有的技能；记录不存在或不属于当前范围时统一返回空。 */
    Optional<PersonalSkill> getDetail(SkillScope scope, long skillId);

    /** 按更新时间、ID 倒序分页，状态条件同时作用于总数和当前页。 */
    PersonalSkillPage list(SkillScope scope, SkillListQuery query);

    /** 框架仓库使用：按名称升序读取当前范围内全部已启用技能。 */
    List<PersonalSkill> listEnabled(SkillScope scope);

    /** 框架仓库使用：草稿、停用、不存在及其他归属的技能均返回空。 */
    Optional<PersonalSkill> findEnabledByName(SkillScope scope, String name);

    /** 完整替换内容并保留当前状态，成功后 revision + 1；版本冲突时不写入。 */
    PersonalSkill update(SkillScope scope, long skillId, UpdateSkill content);

    /** 启用技能，校验并递增版本，返回更新后的详情。 */
    PersonalSkill enable(SkillScope scope, long skillId, long revision);

    /** 停用技能，校验并递增版本，返回更新后的详情。 */
    PersonalSkill disable(SkillScope scope, long skillId, long revision);

    /** 在当前范围内按版本物理删除；不存在抛出 SkillNotFoundException，旧版本抛出 SkillRevisionConflictException。 */
    void delete(SkillScope scope, long skillId, long revision);
}
