package com.laoshiren.agent.skills.controller;

import com.laoshiren.agent.skills.agent.SkillAgentService;
import com.laoshiren.agent.skills.param.SkillAgentCall;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.result.SkillAgentCallResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 开发测试入口：沿用请求头模拟身份，返回单次回答及真实技能调用记录。 */
@Profile("dev")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/skill-agent")
public class SkillAgentController {
    private final SkillAgentService service;

    @PostMapping("/calls")
    public SkillAgentCallResult call(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Valid @RequestBody SkillAgentCall request) {
        return service.call(new SkillScope(tenantId, userId), request);
    }
}
