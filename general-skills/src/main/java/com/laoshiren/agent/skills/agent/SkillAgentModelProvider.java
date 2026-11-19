package com.laoshiren.agent.skills.agent;

import io.agentscope.core.model.Model;

@FunctionalInterface
public interface SkillAgentModelProvider {
    Model getModel();
}
