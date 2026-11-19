package com.laoshiren.agent.skills.result;

import java.time.Instant;
import java.util.List;

/**
 * 单次调用记录随响应返回，不保存对话；只记录公开输出和工具执行，不记录模型思考内容。
 */
public record SkillAgentCallResult(String callId, String status, String answer, int modelCalls,
                                   List<DiscoveredSkill> discoveredSkills, List<SkillLoad> skillLoads,
                                   List<Step> steps, Timing timing) {

    public record DiscoveredSkill(String skillId, String name, String description) {
    }

    /**
     * output 是实际工具输出；只有成功取得 SKILL.md 正文时 instructionsLoaded 才为 true。
     */
    public record SkillLoad(
            // tool 调用ID
            String toolCallId,
            // skill Id
            String skillId,
            // 技能名称
            String skillName,
            // 路径
            String path,
            // 状态
            String state,
            boolean instructionsLoaded,
            String output,
            // 是否观察到了工具的实际终态
            boolean terminalStateObserved,
            // 未观察到终态时，记录整次调用结束的原因；否则为 null
            String observationEndReason,
            Timing timing) {
    }

    public record Step(int sequence,
                       String type,
                       String toolCallId,
                       Instant occurredAt,
                       long elapsedMs) {
    }

    public record Timing(
            Instant startedAt,
            Instant endedAt,
            long elapsedMs) {
    }
}
