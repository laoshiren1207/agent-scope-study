package com.laoshiren.agent.skills.controller;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.service.SkillImportService;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 开发测试导入入口，沿用请求头模拟身份；正式入口需改为认证上下文。 */
@Profile("dev")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/personal-skills")
public class PersonalSkillImportController {
    private final SkillImportService imports;

    /** 单文件导入，成功返回新建草稿和详情 Location。 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PersonalSkill> importDraft(
            @RequestHeader("X-Tenant-Id") long tenantId,
            @RequestHeader("X-User-Id") long userId,
            @RequestParam("file") List<MultipartFile> files) {
        Assert.isTrue(files.size() == 1, "每次只能导入一个 SKILL.md 文件");
        var created = imports.importDraft(new SkillScope(tenantId, userId), files.get(0));
        return ResponseEntity.created(URI.create("/api/personal-skills/" + created.getId())).body(created);
    }
}
