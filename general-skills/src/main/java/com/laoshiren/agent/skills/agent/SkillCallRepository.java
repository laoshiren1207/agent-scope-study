package com.laoshiren.agent.skills.agent;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.List;

/**
 * 本次调用开始时取得的数据库快照。保证目录、正文、调用记录使用同一份内容，
 * 并在进入框架前暴露数据库读取错误，避免框架把仓库异常降级为空目录。
 */
final class SkillCallRepository implements AgentSkillRepository {
    private final List<AgentSkill> skills;
    private final String source;
    private final AgentSkillRepositoryInfo info;
    private final SkillCallTrace trace;

    SkillCallRepository(AgentSkillRepository repository, SkillCallTrace trace) {
        this.skills = List.copyOf(repository.getAllSkills());
        this.source = repository.getSource();
        this.info = repository.getRepositoryInfo();
        this.trace = trace;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        trace.discover(skills);
        return skills;
    }

    @Override
    public List<String> getAllSkillNames() {
        return skills.stream().map(AgentSkill::getName).toList();
    }

    @Override
    public AgentSkill getSkill(String name) {
        return skills.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
    }

    @Override public boolean skillExists(String name) { return getSkill(name) != null; }
    @Override public String getSource() { return source; }
    @Override public AgentSkillRepositoryInfo getRepositoryInfo() { return info; }
    @Override public boolean isWriteable() { return false; }
    @Override public void setWriteable(boolean writeable) { if (writeable) throw readOnly(); }
    @Override public boolean save(List<AgentSkill> values, boolean force) { throw readOnly(); }
    @Override public boolean delete(String name) { throw readOnly(); }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("单次调用的技能快照只读");
    }
}
