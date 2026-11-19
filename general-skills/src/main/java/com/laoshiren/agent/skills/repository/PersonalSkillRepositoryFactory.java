package com.laoshiren.agent.skills.repository;

import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 工厂可以共享；产出的仓库固定身份，可交给 AgentScope 的 skillRepository 配置。 */
@Component
@RequiredArgsConstructor
public class PersonalSkillRepositoryFactory {
    private final IPersonalSkillService skills;

    /** scope 必须来自服务端当前身份；开发 Controller 使用请求头模拟。 */
    public AgentSkillRepository forScope(SkillScope scope) {
        return new PersonalSkillRepository(skills, scope);
    }
}
