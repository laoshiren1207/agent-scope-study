package com.laoshiren.agent.persistent.service;

import com.laoshiren.agent.persistent.api.ChunkRequests.Chunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Markdown 学习示例分片器：识别 ATX 标题，忽略围栏代码块中的标题，并限制每片正文长度。
 * 行号从 1 开始；长度按 Java 字符数限制，不代表模型 Token 数。
 */
@Component
public class MarkdownChunker {
    static final int MAX_CHARS = 2000;
    static final int MAX_CHUNKS = 200;
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    /**
     * 将 UTF-8 解码后的文档切为有序片段，保留 Markdown 正文及章节路径、原文行号。
     *
     * @param markdown Markdown 全文，允许 UTF-8 BOM、CRLF 或 CR 换行
     * @param filename 用于无标题段落的标题和文件来源属性
     * @return 可直接交给片段入库服务的列表；空白文档或片段超过上限时抛出参数异常
     */
    public List<Chunk> split(String markdown, String filename) {
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String[] lines = text.split("\n", -1);
        List<Chunk> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        String title = filename;
        StringBuilder section = new StringBuilder();
        int sectionLine = 1;
        boolean hasBody = false;
        char fenceChar = 0;
        int fenceLength = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            var heading = HEADING.matcher(line);
            // 遇到新章节时先保存上一节；连续的文档标题和章节标题与其后的正文保留在一起。
            if (fenceChar == 0 && heading.matches()) {
                if (hasBody) {
                    appendSection(chunks, section.toString(), title, headings, sectionLine, filename);
                    section.setLength(0);
                    sectionLine = i + 1;
                    hasBody = false;
                }
                int level = heading.group(1).length();
                title = heading.group(2).replaceFirst("[ \\t]+#+[ \\t]*$", "").strip();
                while (headings.size() >= level) headings.remove(headings.size() - 1);
                while (headings.size() < level - 1) headings.add("");
                headings.add(title);
            } else if (!line.isBlank()) {
                hasBody = true;
            }

            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceChar == 0) {
                    fenceChar = marker.charAt(0);
                    fenceLength = marker.length();
                } else if (marker.charAt(0) == fenceChar && marker.length() >= fenceLength
                        && fence.group(2).isBlank()) {
                    fenceChar = 0;
                }
            }
            section.append(line);
            if (i < lines.length - 1) section.append('\n');
        }
        appendSection(chunks, section.toString(), title, headings, sectionLine, filename);
        if (chunks.isEmpty()) throw new IllegalArgumentException("Markdown 文件没有可入库的正文");
        return List.copyOf(chunks);
    }

    /** 长章节优先在换行处拆开；单行过长时硬切，避免拆开 Unicode 代理对。 */
    private void appendSection(List<Chunk> chunks, String text, String title, List<String> headings,
                                int firstLine, String filename) {
        List<String> path = headings.stream().filter(item -> !item.isBlank()).toList();
        int cursor = 0;
        int line = firstLine;
        while (cursor < text.length()) {
            int end = Math.min(cursor + MAX_CHARS, text.length());
            if (end < text.length()) {
                if (Character.isHighSurrogate(text.charAt(end - 1))
                        && Character.isLowSurrogate(text.charAt(end))) end--;
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline >= cursor) end = newline + 1;
            }
            int start = cursor;
            int contentEnd = end;
            while (start < contentEnd && Character.isWhitespace(text.charAt(start))) start++;
            while (contentEnd > start && Character.isWhitespace(text.charAt(contentEnd - 1))) contentEnd--;
            if (start < contentEnd) {
                if (chunks.size() >= MAX_CHUNKS) {
                    throw new IllegalArgumentException("Markdown 分片数量超过 200，请拆成更小的文件上传");
                }
                int lineStart = line + countNewlines(text, cursor, start);
                int lineEnd = lineStart + countNewlines(text, start, contentEnd);
                chunks.add(new Chunk(title, text.substring(start, contentEnd), null,
                        Map.of("section_path", path, "line_start", lineStart, "line_end", lineEnd),
                        Map.of("filename", filename, "chunking", "markdown-atx-char-v1")));
            }
            line += countNewlines(text, cursor, end);
            cursor = end;
        }
    }

    /** 统计指定字符区间中的换行，用于将分片位置换算成原文行号。 */
    private int countNewlines(String text, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) if (text.charAt(i) == '\n') count++;
        return count;
    }
}
