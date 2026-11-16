package com.laoshiren.agent.persistent.api;

import com.laoshiren.agent.persistent.service.KbChunkService;
import com.laoshiren.agent.persistent.service.MarkdownUploadService;
import com.laoshiren.agent.persistent.service.RagRerankAnswerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/kb/chunks")
public class KbChunkController {
    private final KbChunkService chunks;
    private final RagRerankAnswerService answers;
    private final MarkdownUploadService uploads;


    /**
     * 上传单个 Markdown 文件，服务端完成分片、向量化和数据库保存。
     * 仅 file 必填；默认租户和知识库为 1，文档 ID 自动生成，版本号默认为 1。
     */
    @PostMapping(value = "/upload-markdown", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChunkResponses.UploadedDocument uploadMarkdown(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(defaultValue = "1") long tenantId,
            @RequestParam(defaultValue = "1") long knowledgeBaseId,
            @RequestParam(required = false) Long documentId,
            @RequestParam(defaultValue = "1") int documentVersion) {
        if (files.size() != 1) throw new IllegalArgumentException("每次只能上传一个 Markdown 文件");
        return uploads.upload(files.get(0), tenantId, knowledgeBaseId, documentId, documentVersion);
    }
//
//    @PostMapping("/ingest")
//    public List<ChunkResponses.Ingested> ingest(@Valid @RequestBody ChunkRequests.Ingest request) {
//        return chunks.ingest(request);
//    }
//

    /**
     * 根据问题检索知识库，直接返回命中的原文片段（hits）。
     *
     * <p>Controller 校验请求参数；Service 生成问题向量；Mapper 执行 pgvector 相似度查询。
     *
     * @param request 租户、知识库、问题，以及可选的返回数量和最低相似度
     * @return 按相似度从高到低排列的片段列表；没有命中时返回 []
     */
    @PostMapping("/search")
    public List<ChunkResponses.Hit> search(@Valid @RequestBody ChunkRequests.Search request) {
        return chunks.search(request);
    }

    /**
     * 问题向量化 → 数据库召回 → TEI 重排 → 分数过滤 → 基于最终资料生成回答。
     * request.topK/minScore 控制向量召回；重排数量和阈值读取 rag.rerank。
     */
    @PostMapping("/ask")
    public ChunkResponses.RerankAnswer ask(@Valid @RequestBody ChunkRequests.Search request) {
        return answers.ask(request);
    }
//
//    @PostMapping("/archive-version")
//    public Map<String, Integer> archive(@Valid @RequestBody ChunkRequests.VersionScope request) {
//        return Map.of("affectedRows", chunks.archive(request));
//    }
//
//    @PostMapping("/delete-version")
//    public Map<String, Integer> delete(@Valid @RequestBody ChunkRequests.VersionScope request) {
//        return Map.of("affectedRows", chunks.delete(request));
//    }
}
