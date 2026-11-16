package com.laoshiren.agent.persistent.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.laoshiren.agent.persistent.api.ChunkRequests;
import com.laoshiren.agent.persistent.api.ChunkRequests.Ingest;
import com.laoshiren.agent.persistent.api.ChunkResponses;
import com.laoshiren.agent.persistent.api.ChunkResponses.UploadedDocument;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Markdown 上传入口服务：校验文件、读取正文、分片，再复用已有的向量入库链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkdownUploadService {
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private final MarkdownChunker chunker;
    private final KbChunkService chunks;


    /**
     * 同步处理一个 UTF-8 Markdown 文件，并返回文档 ID 及每个片段的处理状态。
     *
     * <p>只保存分片和上传来源标识，不将原始附件另存到磁盘。未指定 documentId 时，
     * 使用 MyBatis-Plus IdWorker 生成新的文档业务 ID；后续修订可显式传回该 ID 并递增版本号。
     *
     * @param file            单个非空的 .md 文件，最大 1 MiB
     * @param tenantId        片段所属租户
     * @param knowledgeBaseId 片段所属知识库
     * @param documentId      可选的稳定文档业务 ID
     * @param documentVersion 文档入库版本，从 1 开始
     * @return 文档归属、文件名、分片数量以及各片段的入库状态
     */
    public UploadedDocument upload(MultipartFile file, long tenantId, long knowledgeBaseId,
                                   Long documentId, int documentVersion) {
        if (tenantId <= 0 || knowledgeBaseId <= 0 || documentVersion <= 0 || (documentId != null && documentId <= 0)) {
            throw new IllegalArgumentException("租户、知识库、文档 ID 和版本号必须大于 0");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传的 Markdown 文件不能为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new MaxUploadSizeExceededException(MAX_FILE_BYTES);
        }
        // 浏览器可能提供完整客户端路径，只保留最后一个文件名作为来源标识。
        String original = file.getOriginalFilename();
        String filename = original == null ? "" : original.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalArgumentException("只支持上传 .md 文件");
        }

        String markdown;
        try {
            // 严格校验 UTF-8，避免解码替换字符后将乱码正文存入知识库。
            markdown = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes())).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Markdown 文件必须使用 UTF-8 编码", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传的 Markdown 文件", exception);
        }
        if (markdown.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Markdown 正文不能包含空字符，请上传 UTF-8 文本文件");
        }
//
        // 完成全文校验和分片后才写数据库，避免分片数量超限时留下部分记录。
        List<ChunkRequests.Chunk> pieces = chunker.split(markdown, filename);
//
        long effectiveDocumentId = documentId == null ? IdWorker.getId() : documentId;
//        // ingest
        Ingest ingest = new Ingest(tenantId, knowledgeBaseId, effectiveDocumentId, documentVersion, "upload:" + filename, pieces);
        List<ChunkResponses.Ingested> result = chunks.ingest(ingest);
        return new UploadedDocument(tenantId, knowledgeBaseId, effectiveDocumentId,
                documentVersion, filename, pieces.size(), result);
    }
}
