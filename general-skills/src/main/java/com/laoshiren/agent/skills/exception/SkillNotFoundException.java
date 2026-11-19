package com.laoshiren.agent.skills.exception;

/** 不存在与不属于当前调用者的数据统一视为不可见。 */
public class SkillNotFoundException extends RuntimeException {
    public SkillNotFoundException() {
        super("技能不存在");
    }
}
