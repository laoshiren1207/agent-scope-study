package com.laoshiren.agent.skills.param;

import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 从第 1 页开始；status 为空时查询当前用户的所有状态。 */
public record SkillListQuery(
        @Min(value = 1, message = "页码必须大于等于 1") int page,
        @Min(value = 1, message = "每页条数必须大于等于 1")
        @Max(value = 100, message = "每页条数不能超过 100") int size,
        PersonalSkillStatus status) {
    public long offset() {
        return ((long) page - 1) * size;
    }
}
