package com.laoshiren.agent.document;

import com.laoshiren.agent.rag.result.KnowledgeDocument;
import com.laoshiren.agent.utils.MarkdownChunkUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class MarkdownChunkExample {

    public static void main(String[] args) throws IOException {

        System.out.println("工作目录：" + System.getProperty("user.dir"));

        Path file = Path.of("rag/src/main/resources/knowledge/temp.md");

        List<KnowledgeDocument> documents = MarkdownChunkUtil.loadMarkdown(file);

        System.out.println("知识块数量：" + documents.size());

        for (KnowledgeDocument document : documents) {
            System.out.println("编号：" + document.id());
            System.out.println("内容：\n" + document.content());
        }
    }
}
