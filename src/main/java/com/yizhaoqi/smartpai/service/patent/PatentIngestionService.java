package com.yizhaoqi.smartpai.service.patent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.patent.PatentChunk;
import com.yizhaoqi.smartpai.model.patent.PatentClaim;
import com.yizhaoqi.smartpai.model.patent.PatentClaimElement;
import com.yizhaoqi.smartpai.model.patent.PatentDocument;
import com.yizhaoqi.smartpai.model.patent.PatentSection;
import com.yizhaoqi.smartpai.repository.patent.PatentChunkRepository;
import com.yizhaoqi.smartpai.repository.patent.PatentClaimElementRepository;
import com.yizhaoqi.smartpai.repository.patent.PatentClaimRepository;
import com.yizhaoqi.smartpai.repository.patent.PatentDocumentRepository;
import com.yizhaoqi.smartpai.repository.patent.PatentSectionRepository;
import com.yizhaoqi.smartpai.service.patent.dto.PatentParserResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 专利入库链路入口。
 * 当前阶段先负责创建/复用 patent_documents 记录并清理旧结构化子表，
 * 后续会在这里串起 MinerU 解析、专利结构化解析和专利向量化。
 */
//这个是用来处理专利入库的类

@Service
public class PatentIngestionService {

    public static final String PARSER_VERSION = "patent-parser-v1";

    private final PatentDocumentRepository patentDocumentRepository;
    private final PatentSectionRepository patentSectionRepository;
    private final PatentClaimRepository patentClaimRepository;
    private final PatentClaimElementRepository patentClaimElementRepository;
    private final PatentChunkRepository patentChunkRepository;
    private final ObjectMapper objectMapper;

    public PatentIngestionService(PatentDocumentRepository patentDocumentRepository,
                                  PatentSectionRepository patentSectionRepository,
                                  PatentClaimRepository patentClaimRepository,
                                  PatentClaimElementRepository patentClaimElementRepository,
                                  PatentChunkRepository patentChunkRepository,
                                  ObjectMapper objectMapper) {
        this.patentDocumentRepository = patentDocumentRepository;
        this.patentSectionRepository = patentSectionRepository;
        this.patentClaimRepository = patentClaimRepository;
        this.patentClaimElementRepository = patentClaimElementRepository;
        this.patentChunkRepository = patentChunkRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PatentDocument begin(FileProcessingTask task, FileUpload fileUpload) {
        PatentDocument document = patentDocumentRepository.findByUploadId(fileUpload.getId())
                .orElseGet(PatentDocument::new);

        if (document.getId() != null) {
            patentChunkRepository.deleteByPatentId(document.getId());
            patentClaimElementRepository.deleteByPatentId(document.getId());
            patentClaimRepository.deleteByPatentId(document.getId());
            patentSectionRepository.deleteByPatentId(document.getId());
        }

        document.setUploadId(fileUpload.getId());
        document.setFileMd5(task.getFileMd5());
        document.setFileName(task.getFileName());
        document.setUserId(task.getUserId());
        document.setOrgTag(task.getOrgTag());
        document.setPublic(task.isPublic());
        document.setParserVersion(PARSER_VERSION);
        document.setParseStatus(PatentDocument.STATUS_PROCESSING);
        document.setParseError(null);
        document.setParsedAt(null);

        return patentDocumentRepository.save(document);
    }

    @Transactional
    public PatentDocument saveParserResult(Long patentId, PatentParserResult result) {
        PatentDocument document = patentDocumentRepository.findById(patentId)
                .orElseThrow(() -> new IllegalArgumentException("Patent document not found: " + patentId));

        applyMetadata(document, result.getMetadata());
        document.setRawParserResultJson(toJson(result));
        document.setParserVersion(result.getParserVersion() != null ? result.getParserVersion() : PARSER_VERSION);
        document.setParseStatus(PatentDocument.STATUS_COMPLETED);
        document.setParseError(null);
        document.setParsedAt(LocalDateTime.now());
        PatentDocument savedDocument = patentDocumentRepository.save(document);

        Map<Integer, Long> claimIdByNo = saveClaims(savedDocument.getId(), result.getClaims());
        Map<Integer, Long> sectionIdByOrder = saveSections(savedDocument.getId(), result.getSections());
        saveClaimElements(savedDocument.getId(), result.getClaims(), claimIdByNo);
        saveChunks(savedDocument.getId(), result.getChunks(), result.getClaims(), claimIdByNo, sectionIdByOrder);

        return savedDocument;
    }

    private void applyMetadata(PatentDocument document, PatentParserResult.PatentMetadata metadata) {
        if (metadata == null) {
            return;
        }

        document.setApplicationNo(metadata.getApplicationNumber());
        document.setPublicationNo(metadata.getPublicationNumber());
        document.setTitle(repairMojibake(metadata.getTitle()));
        document.setApplicant(repairMojibake(metadata.getApplicant()));
        document.setInventor(repairMojibake(metadata.getInventors()));
        document.setIpcClassification(repairMojibake(metadata.getIpc()));
        document.setPatentType(repairMojibake(metadata.getPatentType()));
        document.setApplicationDate(parsePatentDate(metadata.getApplicationDate()));
        document.setPublicationDate(parsePatentDate(metadata.getPublicationDate()));
        document.setAgency(repairMojibake(metadata.getAgency()));
        document.setAgent(repairMojibake(metadata.getAgent()));
        document.setAddress(repairMojibake(metadata.getAddress()));
        document.setAbstractText(repairMojibake(metadata.getAbstractText()));
        document.setMainClaimText(repairMojibake(metadata.getMainClaimText()));
        document.setRawBibliographicJson(metadata.getRawBibliographicJson() != null
                ? metadata.getRawBibliographicJson()
                : toJson(metadata));
    }

    private Map<Integer, Long> saveClaims(Long patentId, List<PatentParserResult.PatentClaimItem> claims) {
        Map<Integer, Long> claimIdByNo = new HashMap<>();
        if (claims == null || claims.isEmpty()) {
            return claimIdByNo;
        }

        for (PatentParserResult.PatentClaimItem item : claims) {
            if (item.getClaimNo() == null || item.getText() == null || item.getText().isBlank()) {
                continue;
            }

            PatentClaim claim = new PatentClaim();
            claim.setPatentId(patentId);
            claim.setClaimNo(item.getClaimNo());
            claim.setTextContent(repairMojibake(item.getText()));
            claim.setIndependent(item.isIndependent());
            claim.setDependsOnClaimNo(item.getDependsOnClaimNo());
            claim.setTechnicalFeaturesJson(repairMojibake(item.getTechnicalFeaturesJson()));
            claim.setPageNumber(item.getPageNumber());
            claim.setAnchorText(repairMojibake(item.getAnchorText()));
            PatentClaim saved = patentClaimRepository.save(claim);
            claimIdByNo.put(saved.getClaimNo(), saved.getId());
        }

        return claimIdByNo;
    }

    private void saveClaimElements(Long patentId,
                                   List<PatentParserResult.PatentClaimItem> claims,
                                   Map<Integer, Long> claimIdByNo) {
        if (claims == null || claims.isEmpty()) {
            return;
        }

        for (PatentParserResult.PatentClaimItem claim : claims) {
            if (claim.getClaimNo() == null || claim.getText() == null || claim.getText().isBlank()) {
                continue;
            }
            Long claimId = claimIdByNo.get(claim.getClaimNo());
            if (claimId == null) {
                continue;
            }

            List<String> features = extractClaimFeatures(claim);
            int elementNo = 1;
            for (String rawFeature : features) {
                String text = normalizeElementText(repairMojibake(rawFeature));
                if (text == null || text.isBlank()) {
                    continue;
                }

                PatentClaimElement element = new PatentClaimElement();
                element.setPatentId(patentId);
                element.setClaimId(claimId);
                element.setClaimNo(claim.getClaimNo());
                element.setElementNo(elementNo);
                element.setElementLabel("F" + elementNo);
                element.setElementType(classifyElementType(text, elementNo, claim.isIndependent()));
                element.setTextContent(text);
                element.setNormalizedText(normalizeElementText(text));
                element.setCore(claim.isIndependent() || claim.getClaimNo() == 1);
                element.setConfidence(extractConfidence(claim));
                element.setExtractionMethod(claim.getTechnicalFeaturesJson() != null && !claim.getTechnicalFeaturesJson().isBlank()
                        ? PatentClaimElement.METHOD_RULE
                        : PatentClaimElement.METHOD_HYBRID);
                element.setReviewStatus(PatentClaimElement.REVIEW_PENDING);
                patentClaimElementRepository.save(element);
                elementNo++;
            }
        }
    }

    private List<String> extractClaimFeatures(PatentParserResult.PatentClaimItem claim) {
        List<String> features = extractFeaturesFromJson(claim.getTechnicalFeaturesJson());
        if (!features.isEmpty()) {
            return features;
        }
        return splitClaimTextIntoFeatures(claim.getText());
    }

    private List<String> extractFeaturesFromJson(String technicalFeaturesJson) {
        List<String> features = new ArrayList<>();
        if (technicalFeaturesJson == null || technicalFeaturesJson.isBlank()) {
            return features;
        }

        try {
            JsonNode root = objectMapper.readTree(technicalFeaturesJson);
            JsonNode featureNode = root.path("features");
            if (featureNode.isArray()) {
                for (JsonNode item : featureNode) {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        features.add(item.asText());
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败时回退到权利要求原文拆分。
        }
        return features;
    }

    private List<String> splitClaimTextIntoFeatures(String text) {
        List<String> features = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return features;
        }

        String cleaned = normalizeElementText(text);
        if (cleaned == null || cleaned.isBlank()) {
            return features;
        }
        String[] parts = cleaned.split("[；;。]\\s*|\\n+");
        for (String part : parts) {
            String normalized = normalizeElementText(part);
            if (normalized != null && normalized.length() >= 8) {
                features.add(normalized);
            }
        }
        if (features.isEmpty()) {
            features.add(cleaned);
        }
        return features;
    }

    private String classifyElementType(String text, int elementNo, boolean independentClaim) {
        if (text == null) {
            return PatentClaimElement.TYPE_COMPONENT;
        }
        if (elementNo == 1 && (text.startsWith("一种") || text.startsWith("一项") || text.startsWith("用于"))) {
            return PatentClaimElement.TYPE_PREAMBLE;
        }
        if (text.contains("步骤") || text.matches(".*\\bS\\d+\\b.*")) {
            return PatentClaimElement.TYPE_STEP;
        }
        if (text.contains("当") || text.contains("若") || text.contains("如果") || text.contains("满足")) {
            return PatentClaimElement.TYPE_CONDITION;
        }
        if (text.contains("参数") || text.contains("阈值") || text.contains("区间") || text.contains("系数")
                || text.contains("公式") || text.contains("=")) {
            return PatentClaimElement.TYPE_PARAMETER;
        }
        if (text.contains("用于") || text.contains("以便") || text.contains("从而")) {
            return PatentClaimElement.TYPE_EFFECT;
        }
        return independentClaim ? PatentClaimElement.TYPE_COMPONENT : PatentClaimElement.TYPE_LIMITATION;
    }

    private BigDecimal extractConfidence(PatentParserResult.PatentClaimItem claim) {
        return claim.getTechnicalFeaturesJson() != null && !claim.getTechnicalFeaturesJson().isBlank()
                ? new BigDecimal("0.8600")
                : new BigDecimal("0.5500");
    }

    private String normalizeElementText(String value) {
        if (value == null) {
            return null;
        }
        return value.trim()
                .replaceAll("^[\\d]+\\s*[.．、]\\s*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[：:；;，,。\\s]+", "")
                .replaceAll("[：:；;，,。\\s]+$", "");
    }

    private Map<Integer, Long> saveSections(Long patentId, List<PatentParserResult.PatentSectionItem> sections) {
        Map<Integer, Long> sectionIdByOrder = new HashMap<>();
        if (sections == null || sections.isEmpty()) {
            return sectionIdByOrder;
        }

        int fallbackOrder = 1;
        for (PatentParserResult.PatentSectionItem item : sections) {
            if (item.getSectionType() == null || item.getSectionType().isBlank()) {
                continue;
            }

            int sectionOrder = item.getOrder() != null ? item.getOrder() : fallbackOrder;
            PatentSection section = new PatentSection();
            section.setPatentId(patentId);
            section.setSectionType(repairMojibake(item.getSectionType()));
            section.setSectionTitle(repairMojibake(item.getTitle()));
            section.setSectionOrder(sectionOrder);
            section.setTextContent(repairMojibake(item.getText()));
            section.setPageStart(item.getPageStart());
            section.setPageEnd(item.getPageEnd());
            section.setAnchorText(repairMojibake(item.getAnchorText()));
            PatentSection saved = patentSectionRepository.save(section);
            sectionIdByOrder.put(sectionOrder, saved.getId());
            fallbackOrder++;
        }

        return sectionIdByOrder;
    }

    private void saveChunks(Long patentId,
                            List<PatentParserResult.PatentChunkItem> chunks,
                            List<PatentParserResult.PatentClaimItem> claims,
                            Map<Integer, Long> claimIdByNo,
                            Map<Integer, Long> sectionIdByOrder) {
        boolean hasParserChunks = chunks != null && !chunks.isEmpty();
        boolean hasClaims = claims != null && !claims.isEmpty();
        if (!hasParserChunks && !hasClaims) {
            return;
        }

        Set<Integer> usedChunkNos = new HashSet<>();
        int fallbackChunkNo = 1;
        if (hasClaims && !containsClaimChunk(chunks)) {
            for (PatentParserResult.PatentClaimItem claim : claims) {
                if (claim.getClaimNo() == null || claim.getText() == null || claim.getText().isBlank()) {
                    continue;
                }
                PatentChunk chunk = new PatentChunk();
                chunk.setPatentId(patentId);
                chunk.setSourceType(PatentChunk.SOURCE_CLAIM);
                chunk.setSourceId(claimIdByNo.get(claim.getClaimNo()));
                chunk.setChunkNo(nextChunkNo(usedChunkNos, fallbackChunkNo++));
                chunk.setTextContent(repairMojibake(claim.getText()));
                chunk.setPageNumber(claim.getPageNumber());
                chunk.setAnchorText(repairMojibake(claim.getAnchorText()));
                chunk.setClaimNo(claim.getClaimNo());
                chunk.setIndependentClaim(claim.isIndependent());
                patentChunkRepository.save(chunk);
            }
        }

        if (!hasParserChunks) {
            return;
        }

        for (PatentParserResult.PatentChunkItem item : chunks) {
            if (item.getSourceType() == null || item.getText() == null || item.getText().isBlank()) {
                continue;
            }

            PatentChunk chunk = new PatentChunk();
            chunk.setPatentId(patentId);
            chunk.setSourceType(repairMojibake(item.getSourceType()));
            chunk.setSourceId(resolveSourceId(item, claimIdByNo, sectionIdByOrder));
            chunk.setChunkNo(nextChunkNo(usedChunkNos, item.getChunkNo() != null ? item.getChunkNo() : fallbackChunkNo));
            chunk.setTextContent(repairMojibake(item.getText()));
            chunk.setPageNumber(item.getPageNumber());
            chunk.setAnchorText(repairMojibake(item.getAnchorText()));
            chunk.setSectionPath(repairMojibake(item.getSectionPath()));
            chunk.setClaimNo(item.getClaimNo());
            chunk.setIndependentClaim(item.isIndependentClaim());
            chunk.setTokenCount(item.getTokenCount());
            patentChunkRepository.save(chunk);
            fallbackChunkNo++;
        }
    }

    private boolean containsClaimChunk(List<PatentParserResult.PatentChunkItem> chunks) {
        return chunks != null && chunks.stream()
                .anyMatch(item -> PatentChunk.SOURCE_CLAIM.equals(item.getSourceType()));
    }

    private int nextChunkNo(Set<Integer> usedChunkNos, int preferred) {
        int candidate = Math.max(1, preferred);
        while (usedChunkNos.contains(candidate)) {
            candidate++;
        }
        usedChunkNos.add(candidate);
        return candidate;
    }

    private Long resolveSourceId(PatentParserResult.PatentChunkItem item,
                                 Map<Integer, Long> claimIdByNo,
                                 Map<Integer, Long> sectionIdByOrder) {
        if (item.getSourceId() != null) {
            return item.getSourceId();
        }
        if (PatentChunk.SOURCE_CLAIM.equals(item.getSourceType()) && item.getClaimNo() != null) {
            return claimIdByNo.get(item.getClaimNo());
        }
        if (PatentChunk.SOURCE_DESCRIPTION.equals(item.getSourceType()) && item.getChunkNo() != null) {
            return sectionIdByOrder.get(item.getChunkNo());
        }
        return null;
    }

    @Transactional
    public void markCompleted(Long patentId) {
        patentDocumentRepository.findById(patentId).ifPresent(document -> {
            document.setParseStatus(PatentDocument.STATUS_COMPLETED);
            document.setParseError(null);
            document.setParsedAt(LocalDateTime.now());
            patentDocumentRepository.save(document);
        });
    }

    @Transactional
    public void markFailed(Long patentId, String errorMessage) {
        patentDocumentRepository.findById(patentId).ifPresent(document -> {
            document.setParseStatus(PatentDocument.STATUS_FAILED);
            document.setParseError(errorMessage);
            document.setParsedAt(LocalDateTime.now());
            patentDocumentRepository.save(document);
        });
    }

    private LocalDate parsePatentDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim()
                .replace('年', '-')
                .replace('月', '-')
                .replace("日", "")
                .replace('.', '-')
                .replace('/', '-');

        String[] parts = normalized.split("-");
        if (parts.length >= 3) {
            normalized = String.format("%s-%02d-%02d",
                    parts[0],
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        }

        try {
            return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String repairMojibake(String value) {
        if (value == null || value.isBlank() || !looksLikeUtf8Mojibake(value)) {
            return value;
        }
        try {
            return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private boolean looksLikeUtf8Mojibake(String value) {
        return value.indexOf('å') >= 0
                || value.indexOf('ç') >= 0
                || value.indexOf('è') >= 0
                || value.indexOf('é') >= 0
                || value.indexOf('ä') >= 0
                || value.indexOf('æ') >= 0
                || value.indexOf('ã') >= 0;
    }
}
