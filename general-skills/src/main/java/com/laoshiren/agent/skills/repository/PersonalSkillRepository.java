package com.laoshiren.agent.skills.repository;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.Assert;

/**
 * 绑定单个租户、用户的框架技能仓库。每次读取查询数据库，只暴露已启用技能。
 * 通过工厂为当前身份创建实例；不要把某个用户的仓库共享给其他用户。
 * 写操作仍走技能管理服务，以保留校验、revision 和更新时间规则。
 */
public final class PersonalSkillRepository implements AgentSkillRepository {
    private final IPersonalSkillService skills;
    private final SkillScope scope;
    private final String source;

    PersonalSkillRepository(IPersonalSkillService skills, SkillScope scope) {
        Assert.notNull(skills, "技能服务不能为空");
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        this.skills = skills;
        this.scope = scope;
        this.source = "personal-db-t" + scope.tenantId() + "-u" + scope.userId();
    }

    /** 按业务名称加载，不接收数据库 ID 或框架 skillId；不可见时返回 null。 */
    @Override
    public AgentSkill getSkill(String name) {
        return skills.findEnabledByName(scope, name).map(this::toAgentSkill).orElse(null);
    }

    @Override
    public List<String> getAllSkillNames() {
        return skills.listEnabled(scope).stream().map(PersonalSkill::getName).toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return skills.listEnabled(scope).stream().map(this::toAgentSkill).toList();
    }

    @Override
    public boolean skillExists(String name) {
        return skills.findEnabledByName(scope, name).isPresent();
    }

    private AgentSkill toAgentSkill(PersonalSkill row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (row.getExtraFrontmatter() != null) metadata.putAll(row.getExtraFrontmatter());
        // 独立列是名称、描述的唯一来源，扩展属性不能覆盖它们。
        metadata.put("name", row.getName());
        metadata.put("description", row.getDescription());
        return new AgentSkill(metadata, row.getInstructionsMd(), Map.of(), source);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("postgresql",
                "personal_skill/tenant/" + scope.tenantId() + "/user/" + scope.userId(), false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public void setWriteable(boolean writeable) {
        if (writeable) throw readOnly();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        throw readOnly();
    }

    @Override
    public boolean delete(String skillName) {
        throw readOnly();
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("个人技能仓库只读，请通过技能管理服务修改技能");
    }
}
