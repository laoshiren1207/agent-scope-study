package com.laoshiren.agent.skills.param;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 启用、停用时提交最后一次读取到的版本。 */
public record SkillRevision(
        @NotNull(message = "版本号不能为空") @Positive(message = "版本号必须为正数") Long revision) {
}
