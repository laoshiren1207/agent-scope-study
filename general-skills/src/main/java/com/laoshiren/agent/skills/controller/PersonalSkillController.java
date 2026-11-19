package com.laoshiren.agent.skills.controller;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.exception.SkillNotFoundException;
import com.laoshiren.agent.skills.param.CreateSkillDraft;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.SkillRevision;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.param.UpdateSkill;
import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;
import com.laoshiren.agent.skills.result.PersonalSkillPage;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import io.agentscope.core.skill.AgentSkill;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 开发测试入口：请求头仅用于模拟身份，正式入口需改为从认证上下文取得 SkillScope。 */
@Profile("dev")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/personal-skills")
public class PersonalSkillController {
    private final IPersonalSkillService service;
    private final PersonalSkillRepositoryFactory repositories;

    /** 框架仓库视图：只列出当前租户、用户已启用的技能名称。 */
    @GetMapping("/repository")
    public List<String> listRepositorySkills(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId) {
        return repositories.forScope(new SkillScope(tenantId, userId)).getAllSkillNames();
    }

    /** 按名称加载框架 AgentSkill；未启用或不属于当前身份时统一返回 404。 */
    @GetMapping("/repository/{name}")
    public AgentSkill loadRepositorySkill(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @PathVariable String name) {
        var skill = repositories.forScope(new SkillScope(tenantId, userId)).getSkill(name);
        if (skill == null) throw new SkillNotFoundException();
        return skill;
    }

    /** 创建当前模拟用户的草稿，返回详情及可用于查询的 Location。 */
    @PostMapping
    public ResponseEntity<PersonalSkill> createDraft(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Valid @RequestBody CreateSkillDraft draft) {
        var created = service.createDraft(new SkillScope(tenantId, userId), draft);
        return ResponseEntity.created(URI.create("/api/personal-skills/" + created.getId())).body(created);
    }

    /** 不存在及不属于当前租户、用户的记录统一返回 404。 */
    @GetMapping("/{skillId}")
    public PersonalSkill getDetail(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Positive(message = "技能 ID 必须为正数") @PathVariable long skillId) {
        return service.getDetail(new SkillScope(tenantId, userId), skillId)
                .orElseThrow(SkillNotFoundException::new);
    }

    /** 查询当前用户的技能列表，支持分页及状态筛选。 */
    @GetMapping
    public PersonalSkillPage list(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) PersonalSkillStatus status) {
        return service.list(new SkillScope(tenantId, userId), new SkillListQuery(page, size, status));
    }

    /** 按 revision 完整替换内容，保留当前启停状态。 */
    @PutMapping("/{skillId}")
    public PersonalSkill update(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Positive @PathVariable long skillId,
            @Valid @RequestBody UpdateSkill content) {
        return service.update(new SkillScope(tenantId, userId), skillId, content);
    }

    @PostMapping("/{skillId}/enable")
    public PersonalSkill enable(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Positive @PathVariable long skillId,
            @Valid @RequestBody SkillRevision request) {
        return service.enable(new SkillScope(tenantId, userId), skillId, request.revision());
    }

    @PostMapping("/{skillId}/disable")
    public PersonalSkill disable(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Positive @PathVariable long skillId,
            @Valid @RequestBody SkillRevision request) {
        return service.disable(new SkillScope(tenantId, userId), skillId, request.revision());
    }

    /** 按当前版本删除，成功返回 204；旧版本不能删除已更新的记录。 */
    @DeleteMapping("/{skillId}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @Positive @PathVariable long skillId,
            @Positive @RequestParam long revision) {
        service.delete(new SkillScope(tenantId, userId), skillId, revision);
        return ResponseEntity.noContent().build();
    }
}
