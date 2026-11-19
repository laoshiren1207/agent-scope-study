package com.laoshiren.agent.skills.param;

/** 当前调用者的数据范围；由可信服务端身份构造，不能直接使用客户端提交的归属字段。 */
public record SkillScope(long tenantId, long userId) {
    public SkillScope {
        if (tenantId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("租户 ID 和用户 ID 必须为正数");
        }
    }
}
