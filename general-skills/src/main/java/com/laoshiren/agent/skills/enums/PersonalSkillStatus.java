package com.laoshiren.agent.skills.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 个人技能状态，持久化时使用数据库约定的小写值。 */
@Getter
@RequiredArgsConstructor
public enum PersonalSkillStatus {
    /** 草稿。 */
    DRAFT("draft"),
    /** 启用。 */
    ENABLED("enabled"),
    /** 停用。 */
    DISABLED("disabled");

    @EnumValue
    private final String value;
}
