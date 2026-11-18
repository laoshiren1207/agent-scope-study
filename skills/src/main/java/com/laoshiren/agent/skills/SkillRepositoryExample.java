package com.laoshiren.agent.skills;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class SkillRepositoryExample {

    public static void main(String[] args) {
        try (ClasspathSkillRepository repository =
                     new ClasspathSkillRepository("skills")) {
            AgentSkill skill = repository.getSkill("after-sales");

            log.info("名称：{}", skill.getName());
            log.info("简介：{}", skill.getDescription());
        } catch (IOException ignore) {

        }
    }

}
