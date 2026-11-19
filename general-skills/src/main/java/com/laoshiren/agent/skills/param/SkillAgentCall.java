package com.laoshiren.agent.skills.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 单次调用；技能由模型根据当前身份可见的目录选择。 */
public record SkillAgentCall(
        @NotBlank(message = "提问内容不能为空")
        @Size(max = 8000, message = "提问内容不能超过 8000 个字符")
        String message) {
}
