package com.laoshiren.agent.skills.service;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.param.CreateSkillDraft;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.parser.SkillMarkdownParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkillImportServiceTest {
    private static final SkillScope OWNER = new SkillScope(10L, 100L);
    private static final String MARKDOWN = "---\nname: review\ndescription: 描述\n---\n# 正文\n";
    private IPersonalSkillService skills;
    private SkillImportService imports;

    @BeforeEach
    void setUp() {
        skills = mock(IPersonalSkillService.class);
        imports = new SkillImportService(new SkillMarkdownParser(), skills);
    }

    @Test
    void delegatesParsedContentAndCallerScopeToCreateDraft() {
        var created = new PersonalSkill();
        when(skills.createDraft(any(), any())).thenReturn(created);

        assertThat(imports.importDraft(OWNER, file("C:\\skills\\SKILL.MD", MARKDOWN.getBytes(StandardCharsets.UTF_8))))
                .isSameAs(created);
        verify(skills).createDraft(OWNER, new CreateSkillDraft("review", "描述", "# 正文\n", Map.of()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "SKILL.txt", "SKILL.md.exe"})
    void rejectsNonMarkdownFiles(String name) {
        assertThatThrownBy(() -> imports.importDraft(OWNER, file(name, MARKDOWN.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("只支持上传 .md");
        verifyNoInteractions(skills);
    }

    @Test
    void rejectsMissingScopeFileEmptyFileAndInvalidUtf8() {
        var valid = file("SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> imports.importDraft(null, valid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imports.importDraft(OWNER, null)).hasMessageContaining("请上传");
        assertThatThrownBy(() -> imports.importDraft(OWNER, file("SKILL.md", new byte[0])))
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> imports.importDraft(OWNER, file("SKILL.md", new byte[]{(byte) 0xc3, 0x28})))
                .hasMessageContaining("UTF-8");
        verifyNoInteractions(skills);
    }

    @Test
    void rejectsOversizedFilesBeforeParsingOrCreating() {
        assertThatThrownBy(() -> imports.importDraft(OWNER, file("SKILL.md", new byte[1024 * 1024 + 1])))
                .isInstanceOf(MaxUploadSizeExceededException.class);
        verifyNoInteractions(skills);
    }

    @Test
    void boundsStreamReadEvenWhenReportedSizeIsIncorrect() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("SKILL.md");
        when(file.getSize()).thenReturn(1L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[1024 * 1024 + 1]));

        assertThatThrownBy(() -> imports.importDraft(OWNER, file)).isInstanceOf(MaxUploadSizeExceededException.class);
        verifyNoInteractions(skills);
    }

    @Test
    void reportsUnreadableUploadWithoutCreatingAnything() throws Exception {
        var file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("SKILL.md");
        when(file.getInputStream()).thenThrow(new IOException("internal error"));

        assertThatThrownBy(() -> imports.importDraft(OWNER, file))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("无法读取上传的 SKILL.md 文件");
        verifyNoInteractions(skills);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/markdown", content);
    }
}
