package com.laoshiren.agent.skills.exception;

/** 调用者的版本已过期，必须重新读取后再提交。 */
public class SkillRevisionConflictException extends RuntimeException {
    public SkillRevisionConflictException() {
        super("技能已被修改，请重新查询后使用最新 revision 提交");
    }
}
