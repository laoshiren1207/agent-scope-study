package com.laoshiren.agent.persistent.service;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownChunkerTest {
    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test
    void splitsSectionsAndRecordsHeadingsAndOriginalLineNumbers() {
        var chunks = chunker.split("\uFEFF# 售后\r\n\r\n## 退款\r\n未发货可退款。\r\n\r\n## 退货\r\n七天内退货。", "policy.md");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("# 售后\n\n## 退款\n未发货可退款。");
        assertThat(chunks.get(0).title()).isEqualTo("退款");
        assertThat(chunks.get(0).sourceLocator()).containsEntry("section_path", List.of("售后", "退款"))
                .containsEntry("line_start", 1).containsEntry("line_end", 4);
        assertThat(chunks.get(1).sourceLocator()).containsEntry("section_path", List.of("售后", "退货"))
                .containsEntry("line_start", 6).containsEntry("line_end", 7);
        assertThat(chunks.get(0).tokenCount()).isNull();
    }

    @Test
    void ignoresHeadingsWithinBacktickAndTildeCodeFences() {
        String markdown = "## 示例\n正文\n````md\n## 代码标题\n```\n## 仍是代码\n````\n"
                + "~~~md\n## 另一个代码标题\n~~~\n## 下一节\n正文";
        var chunks = chunker.split(markdown, "code.md");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).contains("## 仍是代码", "## 另一个代码标题");
        assertThat(chunks.get(1).title()).isEqualTo("下一节");
    }

    @Test
    void splitsLongLinesWithoutLosingTextOrBreakingUnicodeCharacters() {
        String markdown = "中".repeat(1999) + "😀" + "文".repeat(2100);
        var chunks = chunker.split(markdown, "long.md");
        assertThat(chunks).hasSize(3);
        assertThat(String.join("", chunks.stream().map(chunk -> chunk.content()).toList()))
                .isEqualTo(markdown);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content().length()).isLessThanOrEqualTo(MarkdownChunker.MAX_CHARS);
            assertThat(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length() - 1)))
                    .isFalse();
            assertThat(Character.isLowSurrogate(chunk.content().charAt(0))).isFalse();
            assertThat(chunk.sourceLocator()).containsEntry("line_start", 1).containsEntry("line_end", 1);
        });
    }

    @Test
    void keepsLineLocationsWhenLongSectionsAreSplitAtNewlines() {
        var chunks = chunker.split("甲".repeat(1500) + "\n\n" + "乙".repeat(1500), "lines.md");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sourceLocator()).containsEntry("line_start", 1).containsEntry("line_end", 1);
        assertThat(chunks.get(1).sourceLocator()).containsEntry("line_start", 3).containsEntry("line_end", 3);
    }

    @Test
    void rejectsBlankMarkdownAndTooManyChunks() {
        assertThatThrownBy(() -> chunker.split("\uFEFF\r\n \t", "empty.md"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有可入库");
        assertThatThrownBy(() -> chunker.split("正文\n## 下一节\n".repeat(201), "big.md"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("超过 200");
    }
}
