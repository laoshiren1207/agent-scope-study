package com.laoshiren.agent.skills.service;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.parser.SkillMarkdownParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/** 上传解析完成后再调用创建服务；所有导入沿用草稿状态、归属校验和创建事务。 */
@Service
@RequiredArgsConstructor
public class SkillImportService {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private final SkillMarkdownParser parser;
    private final IPersonalSkillService skills;

    public PersonalSkill importDraft(SkillScope scope, MultipartFile file) {
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        Assert.notNull(file, "请上传 SKILL.md 文件");
        Assert.isTrue(!file.isEmpty(), "SKILL.md 文件不能为空");
        if (file.getSize() > MAX_FILE_BYTES) throw new MaxUploadSizeExceededException(MAX_FILE_BYTES);

        String filename = file.getOriginalFilename();
        filename = filename == null ? "" : filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        Assert.isTrue(filename.toLowerCase(Locale.ROOT).endsWith(".md"), "只支持上传 .md 文件");

        String markdown;
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new MaxUploadSizeExceededException(MAX_FILE_BYTES);
            markdown = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("SKILL.md 文件必须使用 UTF-8 编码", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传的 SKILL.md 文件", exception);
        }
        return skills.createDraft(scope, parser.parse(markdown));
    }
}
