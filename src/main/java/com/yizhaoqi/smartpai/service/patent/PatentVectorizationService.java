package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.PatentEsDocument;
import com.yizhaoqi.smartpai.model.patent.PatentChunk;
import com.yizhaoqi.smartpai.model.patent.PatentDocument;
import com.yizhaoqi.smartpai.repository.patent.PatentChunkRepository;
import com.yizhaoqi.smartpai.repository.patent.PatentDocumentRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.utils.TextEncodingRepairUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 专利专用向量化服务。
 * 从 patent_chunks 读取结构化块，并写入 Elasticsearch 的 patent_chunks 索引。
 */
@Service
public class PatentVectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(PatentVectorizationService.class);
    private static final int MAX_EMBEDDING_INPUT_TOKENS = 6500;
    private static final int MAX_EMBEDDING_INPUT_CHARS = 6000;
    private static final String SENTENCE_BOUNDARIES = "。！？；;.!?\n";

    private final EmbeddingClient embeddingClient;
    private final ElasticsearchService elasticsearchService;
    private final UsageQuotaService usageQuotaService;
    private final PatentDocumentRepository patentDocumentRepository;
    private final PatentChunkRepository patentChunkRepository;

    public PatentVectorizationService(EmbeddingClient embeddingClient,
                                      ElasticsearchService elasticsearchService,
                                      UsageQuotaService usageQuotaService,
                                      PatentDocumentRepository patentDocumentRepository,
                                      PatentChunkRepository patentChunkRepository) {
        this.embeddingClient = embeddingClient;
        this.elasticsearchService = elasticsearchService;
        this.usageQuotaService = usageQuotaService;
        this.patentDocumentRepository = patentDocumentRepository;
        this.patentChunkRepository = patentChunkRepository;
    }

    @Transactional
    public VectorizationUsageResult vectorizeWithUsage(Long patentId, String requesterId) {
        PatentDocument patentDocument = patentDocumentRepository.findById(patentId)
                .orElseThrow(() -> new IllegalArgumentException("Patent document not found: " + patentId));

        List<PatentChunk> chunks = patentChunkRepository.findByPatentIdOrderByChunkNoAsc(patentId);
        List<PatentChunk> validChunks = chunks.stream()
                .filter(chunk -> chunk.getTextContent() != null && !chunk.getTextContent().isBlank())
                .toList();

        if (validChunks.isEmpty()) {
            logger.warn("[Patent] 未找到可向量化的专利块: patentId={}", patentId);
            elasticsearchService.deletePatentByPatentId(patentId);
            return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
        }

        List<EmbeddingUnit> embeddingUnits = validChunks.stream()
                .flatMap(chunk -> toEmbeddingUnits(chunk).stream())
                .toList();

        List<String> texts = embeddingUnits.stream()
                .map(EmbeddingUnit::text)
                .toList();

        EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                texts,
                requesterId,
                EmbeddingClient.UsageType.UPLOAD
        );
        if (embeddingResult.vectors().size() != embeddingUnits.size()) {
            throw new IllegalStateException("专利向量数量与向量化单元数量不一致: vectors="
                    + embeddingResult.vectors().size() + ", units=" + embeddingUnits.size());
        }

        List<float[]> vectors = embeddingResult.vectors();
        List<PatentEsDocument> esDocuments = IntStream.range(0, embeddingUnits.size())
                .mapToObj(i -> toEsDocument(patentDocument, embeddingUnits.get(i), vectors.get(i), embeddingResult.modelVersion()))
                .toList();

        elasticsearchService.deletePatentByPatentId(patentId);
        elasticsearchService.bulkIndexPatentChunks(esDocuments);

        List<PatentChunk> updatedChunks = new ArrayList<>(validChunks.size());
        for (PatentChunk chunk : validChunks) {
            chunk.setModelVersion(embeddingResult.modelVersion());
            updatedChunks.add(chunk);
        }
        patentChunkRepository.saveAll(updatedChunks);

        logger.info("[Patent] 专利向量化完成: patentId={}, dbChunks={}, embeddingUnits={}, tokens={}",
                patentId, validChunks.size(), embeddingUnits.size(), embeddingResult.totalTokens());
        return new VectorizationUsageResult(
                embeddingResult.totalTokens(),
                embeddingUnits.size(),
                embeddingResult.modelVersion()
        );
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }

    private List<EmbeddingUnit> toEmbeddingUnits(PatentChunk chunk) {
        String text = normalizeEmbeddingText(chunk.getTextContent());
        if (text.isBlank()) {
            return List.of();
        }

        int estimatedTokens = usageQuotaService.estimateTextTokens(text);
        if (estimatedTokens <= MAX_EMBEDDING_INPUT_TOKENS && text.length() <= MAX_EMBEDDING_INPUT_CHARS) {
            return List.of(new EmbeddingUnit(chunk, text, 1, 1));
        }

        List<String> parts = splitLongEmbeddingText(text);
        logger.warn("[Patent] 专利 chunk 过长，已拆分后向量化: chunkId={}, chunkNo={}, chars={}, estimatedTokens={}, parts={}",
                chunk.getId(), chunk.getChunkNo(), text.length(), estimatedTokens, parts.size());
        return IntStream.range(0, parts.size())
                .mapToObj(i -> new EmbeddingUnit(chunk, parts.get(i), i + 1, parts.size()))
                .toList();
    }

    private String normalizeEmbeddingText(String text) {
        if (text == null) {
            return "";
        }
        return TextEncodingRepairUtil.repairMojibake(text).trim()
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .replaceAll("\\s+", " ");
    }

    private List<String> splitLongEmbeddingText(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = findSplitEnd(text, start);
            String part = text.substring(start, end).trim();
            if (!part.isBlank()) {
                parts.add(part);
            }
            start = end;
        }
        return parts;
    }

    private int findSplitEnd(String text, int start) {
        int high = Math.min(text.length(), start + MAX_EMBEDDING_INPUT_CHARS);
        int low = start + 1;
        int best = low;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidate = text.substring(start, mid);
            if (usageQuotaService.estimateTextTokens(candidate) <= MAX_EMBEDDING_INPUT_TOKENS) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (best <= start + 1) {
            best = Math.min(text.length(), start + Math.min(MAX_EMBEDDING_INPUT_CHARS, 1000));
        }
        return retreatToSentenceBoundary(text, start, best);
    }

    private int retreatToSentenceBoundary(String text, int start, int proposedEnd) {
        if (proposedEnd >= text.length()) {
            return text.length();
        }
        int minEnd = start + Math.max(1, (proposedEnd - start) * 2 / 3);
        for (int i = proposedEnd - 1; i >= minEnd; i--) {
            if (SENTENCE_BOUNDARIES.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return proposedEnd;
    }

    private PatentEsDocument toEsDocument(PatentDocument patentDocument,
                                          EmbeddingUnit unit,
                                          float[] vector,
                                          String modelVersion) {
        PatentChunk chunk = unit.chunk();
        return new PatentEsDocument(
                buildEsDocumentId(patentDocument.getId(), chunk.getId(), unit.partIndex(), unit.totalParts()),
                patentDocument.getId(),
                chunk.getId(),
                TextEncodingRepairUtil.repairMojibake(patentDocument.getFileMd5()),
                TextEncodingRepairUtil.repairMojibake(patentDocument.getFileName()),
                chunk.getChunkNo(),
                unit.text(),
                TextEncodingRepairUtil.repairMojibake(chunk.getSourceType()),
                chunk.getSourceId(),
                chunk.getClaimNo(),
                chunk.isIndependentClaim(),
                TextEncodingRepairUtil.repairMojibake(chunk.getSectionPath()),
                chunk.getPageNumber(),
                TextEncodingRepairUtil.repairMojibake(chunk.getAnchorText()),
                vector,
                modelVersion,
                patentDocument.getUserId(),
                patentDocument.getOrgTag(),
                patentDocument.isPublic(),
                patentDocument.getPublicationNo(),
                patentDocument.getApplicationNo(),
                TextEncodingRepairUtil.repairMojibake(patentDocument.getTitle()),
                TextEncodingRepairUtil.repairMojibake(patentDocument.getApplicant()),
                TextEncodingRepairUtil.repairMojibake(patentDocument.getPatentType()),
                formatDate(patentDocument.getPublicationDate()),
                formatDate(patentDocument.getApplicationDate())
        );
    }

    private String buildEsDocumentId(Long patentId, Long chunkId, int partIndex, int totalParts) {
        if (totalParts <= 1) {
            return patentId + ":" + chunkId;
        }
        return patentId + ":" + chunkId + ":" + partIndex;
    }

    private String formatDate(LocalDate value) {
        return value != null ? value.format(DateTimeFormatter.ISO_LOCAL_DATE) : null;
    }

    private record EmbeddingUnit(PatentChunk chunk, String text, int partIndex, int totalParts) {
    }
}
