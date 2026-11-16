package com.laoshiren.agent.utils;

import com.laoshiren.agent.rag.result.KnowledgeDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MarkdownChunkUtil {



    public static List<KnowledgeDocument> loadMarkdown(Path file) throws IOException {

        // 1. 读取整份文件
        String markdown = Files.readString(
                file,
                StandardCharsets.UTF_8
        );

        // 2. 在每行二级标题前切分，保留标题本身
        String[] sections = markdown.split(
                "(?m)(?=^##[ \\t]+)"
        );

        // 3. 将每个章节转换成现有的数据类型
        List<KnowledgeDocument> documents = new ArrayList<>();

        for (String section : sections) {
            String content = section.strip();

            if (content.isEmpty()) {
                continue;
            }

            String id = file.getFileName()
                    + "#chunk-"
                    + (documents.size() + 1);

            documents.add(new KnowledgeDocument(
                    id,
                    List.of(),
                    content
            ));
        }

        return List.copyOf(documents);
    }
}
