package com.laoshiren.agent.skills.result;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import java.util.List;

/** 当前用户的技能分页结果；total 使用与 items 相同的范围及状态条件。 */
public record PersonalSkillPage(int page, int size, long total, List<PersonalSkill> items) {
    public PersonalSkillPage {
        items = List.copyOf(items);
    }
}
