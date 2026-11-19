package com.laoshiren.agent.skills.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.exception.SkillNotFoundException;
import com.laoshiren.agent.skills.exception.SkillRevisionConflictException;
import com.laoshiren.agent.skills.mapper.PersonalSkillMapper;
import com.laoshiren.agent.skills.param.CreateSkillDraft;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.UpdateSkill;
import com.laoshiren.agent.skills.result.PersonalSkillPage;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/** 通过组合 Mapper 避免暴露绕过租户、用户范围的通用 CRUD 方法。 */
@RequiredArgsConstructor
@Service
public class PersonalSkillServiceImpl implements IPersonalSkillService {
    private final PersonalSkillMapper mapper;
    private final Validator validator;

    @Override
    @Transactional
    public PersonalSkill createDraft(SkillScope scope, CreateSkillDraft draft) {
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        // 在 Service 内校验，确保导入、Agent 等非 Web 调用也遵守相同规则。
        validate(draft);
        Map<String, Object> frontmatter = normalizeFrontmatter(draft.extraFrontmatter());

        PersonalSkill skill = new PersonalSkill();
        skill.setTenantId(scope.tenantId());
        skill.setOwnerUserId(scope.userId());
        skill.setName(draft.name());
        skill.setDescription(draft.description());
        skill.setInstructionsMd(draft.instructionsMd());
        skill.setExtraFrontmatter(frontmatter);
        skill.setStatus(PersonalSkillStatus.DRAFT);
        skill.setRevision(1L);
        if (mapper.insert(skill) != 1 || skill.getId() == null) {
            throw new IllegalStateException("创建技能草稿失败");
        }
        // 回读数据库默认时间戳，并沿用同一范围约束；失败时整个创建事务回滚。
        return getDetail(scope, skill.getId())
                .orElseThrow(() -> new IllegalStateException("创建后的技能草稿无法读取"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PersonalSkill> getDetail(SkillScope scope, long skillId) {
        validateIdentity(scope, skillId);
        return Optional.ofNullable(mapper.selectOne(Wrappers.<PersonalSkill>lambdaQuery()
                .eq(PersonalSkill::getId, skillId)
                .eq(PersonalSkill::getTenantId, scope.tenantId())
                .eq(PersonalSkill::getOwnerUserId, scope.userId())));
    }

    @Override
    @Transactional(readOnly = true)
    public PersonalSkillPage list(SkillScope scope, SkillListQuery query) {
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        validate(query);
        long total = mapper.countScoped(scope, query);
        var items = mapper.listScoped(scope, query, query.offset());
        return new PersonalSkillPage(query.page(), query.size(), total, items);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PersonalSkill> listEnabled(SkillScope scope) {
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        return mapper.listEnabledScoped(scope);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PersonalSkill> findEnabledByName(SkillScope scope, String name) {
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        Assert.hasText(name, "技能名称不能为空");
        return Optional.ofNullable(mapper.findEnabledByNameScoped(scope, name));
    }

    @Override
    @Transactional
    public PersonalSkill update(SkillScope scope, long skillId, UpdateSkill content) {
        validateIdentity(scope, skillId);
        validate(content);
        int affected = mapper.updateContentScoped(scope, skillId, content,
                normalizeFrontmatter(content.extraFrontmatter()));
        requireMutation(scope, skillId, affected);
        return requireDetail(scope, skillId);
    }

    @Override
    @Transactional
    public PersonalSkill enable(SkillScope scope, long skillId, long revision) {
        return changeStatus(scope, skillId, revision, PersonalSkillStatus.ENABLED);
    }

    @Override
    @Transactional
    public PersonalSkill disable(SkillScope scope, long skillId, long revision) {
        return changeStatus(scope, skillId, revision, PersonalSkillStatus.DISABLED);
    }

    @Override
    @Transactional
    public void delete(SkillScope scope, long skillId, long revision) {
        validateIdentity(scope, skillId);
        Assert.isTrue(revision > 0, "版本号必须为正数");
        requireMutation(scope, skillId, mapper.deleteScoped(scope, skillId, revision));
    }

    private PersonalSkill changeStatus(SkillScope scope, long skillId, long revision, PersonalSkillStatus status) {
        validateIdentity(scope, skillId);
        Assert.isTrue(revision > 0, "版本号必须为正数");
        requireMutation(scope, skillId, mapper.updateStatusScoped(scope, skillId, revision, status));
        return requireDetail(scope, skillId);
    }

    private PersonalSkill requireDetail(SkillScope scope, long skillId) {
        return getDetail(scope, skillId).orElseThrow(SkillNotFoundException::new);
    }

    private void requireMutation(SkillScope scope, long skillId, int affected) {
        if (affected == 0) {
            // 仅在当前范围回查；不会向其他用户泄漏技能是否存在或它的版本。
            requireDetail(scope, skillId);
            throw new SkillRevisionConflictException();
        }
        if (affected != 1) {
            throw new IllegalStateException("技能操作影响行数异常");
        }
    }

    private static void validateIdentity(SkillScope scope, long skillId) {
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        Assert.isTrue(skillId > 0, "技能 ID 必须为正数");
    }

    private <T> void validate(T request) {
        Assert.notNull(request, "请求参数不能为空");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private static Map<String, Object> normalizeFrontmatter(Map<String, Object> input) {
        Map<String, Object> result = input == null ? Map.of() : new LinkedHashMap<>(input);
        Assert.isTrue(!result.containsKey("name") && !result.containsKey("description"),
                "扩展头部不能包含 name 或 description");
        return result;
    }
}
