package com.laoshiren.agent.skills.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 创建草稿仅接收技能内容；归属、状态、版本号和时间由服务端设置。 */
public record CreateSkillDraft(
        @NotBlank(message = "技能名称不能为空")
        @Size(max = 64, message = "技能名称不能超过 64 个字符")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "技能名称只能包含小写英文字母、数字和单个连字符")
        String name,
        @NotBlank(message = "技能描述不能为空")
        @Size(max = 1024, message = "技能描述不能超过 1024 个字符")
        String description,
        @NotBlank(message = "技能正文不能为空")
        String instructionsMd,
        Map<String, Object> extraFrontmatter) {
}
