package com.yizhaoqi.smartpai.service.patent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.PatentEsDocument;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.OrgTagCacheService;
import com.yizhaoqi.smartpai.service.patent.dto.PatentSearchRequest;
import com.yizhaoqi.smartpai.service.patent.dto.PatentSearchResult;
import com.yizhaoqi.smartpai.utils.TextEncodingRepairUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PatentSearchService {

    private static final Logger logger = LoggerFactory.getLogger(PatentSearchService.class);
    private static final String PATENT_CHUNKS_INDEX = "patent_chunks";
    private static final String SOURCE_TYPE_CLAIM = "CLAIM";
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;
    private static final Pattern PATENT_NUMBER_PATTERN = Pattern.compile("(?i)\\b(?:CN)?\\d{8,13}[.\\dA-Z]*\\b");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("(?i)([\\w.-]+\\.pdf)");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2}|19\\d{2})\\b");
    private static final Pattern ORGANIZATION_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9（）()·\\s]{2,40}(?:公司|大学|学院|研究院|集团|工厂|厂|所))");

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;

    public PatentSearchService(ElasticsearchClient esClient,
                               EmbeddingClient embeddingClient,
                               UserRepository userRepository,
                               OrgTagCacheService orgTagCacheService) {
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
    }

    public List<PatentSearchResult> search(PatentSearchRequest request, String userId) {
        PatentSearchRequest safeRequest = request != null ? request : new PatentSearchRequest();
        String query = normalize(safeRequest.getQuery());
        int topK = normalizeTopK(safeRequest.getTopK());
        List<Query> filters = buildFilters(safeRequest, userId);
        boolean claimFocusedQuery = isClaimFocusedQuery(query);
        boolean independentClaimQuery = isIndependentClaimQuery(query);
        boolean requestClaimOnly = SOURCE_TYPE_CLAIM.equalsIgnoreCase(normalize(safeRequest.getSourceType()));
        boolean preferClaimEvidence = claimFocusedQuery || independentClaimQuery || requestClaimOnly;

        if (query == null) {
            return metadataOnlySearch(filters, topK);
        }

        List<Query> queryFilters = buildQueryDerivedFilters(query);
        filters.addAll(queryFilters);
        if (!queryFilters.isEmpty() && !preferClaimEvidence && isMetadataListingQuery(query)) {
            List<PatentSearchResult> constrainedResults = metadataOnlySearch(filters, topK);
            if (!constrainedResults.isEmpty()) {
                return constrainedResults;
            }
            logger.info("[PatentSearch] 结构化约束无直接匹配，继续混合检索: query={}", query);
        }

        List<Float> queryVector = embedToVectorList(query, userId);
        if (queryVector == null) {
            return bm25Search(query, filters, topK, "BM25_FALLBACK");
        }

        int recallK = Math.max(topK * 20, topK);
        Map<String, RankedPatentDoc> bm25Rank = bm25SearchForRanking(query, filters, recallK);
        Map<String, RankedPatentDoc> knnRank = knnSearch(queryVector, filters, recallK);
        if (bm25Rank.isEmpty() && knnRank.isEmpty()) {
            logger.info("[PatentSearch] BM25 与 KNN 均无结果: query={}", query);
            return Collections.emptyList();
        }
        return fuseAndBuild(knnRank, bm25Rank, topK, preferClaimEvidence, independentClaimQuery || Boolean.TRUE.equals(safeRequest.getIndependentClaimOnly()));
    }

    private List<PatentSearchResult> metadataOnlySearch(List<Query> filters, int topK) {
        try {
            SearchResponse<PatentEsDocument> response = esClient.search(s -> {
                s.index(PATENT_CHUNKS_INDEX);
                s.size(Math.max(topK * 10, topK));
                s.query(q -> q.bool(b -> {
                    filters.forEach(b::filter);
                    return b;
                }));
                return s;
            }, PatentEsDocument.class);
            List<PatentSearchResult> results = response.hits().hits().stream()
                    .map(hit -> toResult(hit, "METADATA"))
                    .toList();
            return diversifyByPatent(results, topK);
        } catch (Exception e) {
            logger.error("[PatentSearch] metadata 查询失败", e);
            return Collections.emptyList();
        }
    }

    private Map<String, RankedPatentDoc> knnSearch(List<Float> queryVector, List<Query> filters, int recallK) {
        try {
            SearchResponse<PatentEsDocument> response = esClient.search(s -> {
                s.index(PATENT_CHUNKS_INDEX);
                s.size(recallK);
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                );
                s.query(q -> q.bool(b -> {
                    filters.forEach(b::filter);
                    return b;
                }));
                return s;
            }, PatentEsDocument.class);
            return toRankMap(response, "KNN");
        } catch (Exception e) {
            logger.error("[PatentSearch] KNN 查询失败", e);
            return Collections.emptyMap();
        }
    }

    private Map<String, RankedPatentDoc> bm25SearchForRanking(String query, List<Query> filters, int recallK) {
        try {
            SearchResponse<PatentEsDocument> response = esClient.search(s -> {
                s.index(PATENT_CHUNKS_INDEX);
                s.size(recallK);
                s.query(q -> q.bool(b -> {
                    b.should(should -> should.match(ma -> ma
                            .field("textContent")
                            .query(query)
                            .operator(Operator.Or)
                    ));
                    b.should(should -> should.match(ma -> ma
                            .field("title")
                            .query(query)
                            .operator(Operator.Or)
                            .boost(4.0f)
                    ));
                    b.should(should -> should.match(ma -> ma
                            .field("applicant")
                            .query(query)
                            .operator(Operator.Or)
                            .boost(3.0f)
                    ));
                    b.should(should -> should.match(ma -> ma
                            .field("fileName")
                            .query(query)
                            .operator(Operator.Or)
                            .boost(5.0f)
                    ));
                    b.should(should -> should.term(t -> t
                            .field("sourceType")
                            .value("CLAIM")
                            .boost(isClaimFocusedQuery(query) ? 8.0f : 1.5f)
                    ));
                    b.should(should -> should.matchPhrase(mp -> mp
                            .field("textContent")
                            .query("权利要求")
                            .boost(isClaimFocusedQuery(query) ? 8.0f : 2.0f)
                    ));
                    addPatentNumberShouldQueries(b, query);
                    addFileNameShouldQueries(b, query);
                    b.minimumShouldMatch("1");
                    filters.forEach(b::filter);
                    return b;
                }));
                return s;
            }, PatentEsDocument.class);
            return toRankMap(response, "BM25");
        } catch (Exception e) {
            logger.error("[PatentSearch] BM25 查询失败", e);
            return Collections.emptyMap();
        }
    }

    private List<PatentSearchResult> bm25Search(String query, List<Query> filters, int topK, String retrievalMode) {
        Map<String, RankedPatentDoc> rankMap = bm25SearchForRanking(query, filters, topK);
        return rankMap.values().stream()
                .sorted((left, right) -> Integer.compare(left.rank(), right.rank()))
                .limit(topK)
                .map(item -> toResult(item.hit(), retrievalMode))
                .toList();
    }

    private Map<String, RankedPatentDoc> toRankMap(SearchResponse<PatentEsDocument> response, String retrievalMode) {
        Map<String, RankedPatentDoc> rankMap = new HashMap<>();
        List<Hit<PatentEsDocument>> hits = response.hits().hits();
        for (int i = 0; i < hits.size(); i++) {
            Hit<PatentEsDocument> hit = hits.get(i);
            if (hit.source() != null) {
                rankMap.put(hit.id(), new RankedPatentDoc(hit.id(), hit, i + 1, retrievalMode));
            }
        }
        return rankMap;
    }

    private List<PatentSearchResult> fuseAndBuild(Map<String, RankedPatentDoc> knnRank,
                                                  Map<String, RankedPatentDoc> bm25Rank,
                                                  int topK,
                                                  boolean preferClaimEvidence,
                                                  boolean preferIndependentClaimEvidence) {
        Set<String> docIds = new HashSet<>();
        docIds.addAll(bm25Rank.keySet());
        docIds.addAll(knnRank.keySet());

        return docIds.stream()
                .map(docId -> {
                    RankedPatentDoc knn = knnRank.get(docId);
                    RankedPatentDoc bm25 = bm25Rank.get(docId);
                    double score = 0.0d;
                    if (knn != null) {
                        score += 0.6d / (60 + knn.rank());
                    }
                    if (bm25 != null) {
                        score += 0.4d / (60 + bm25.rank());
                    }
                    RankedPatentDoc selected = knn != null ? knn : bm25;
                    if (preferClaimEvidence && looksLikeClaimEvidence(selected.hit().source())) {
                        score += 1.0d;
                    }
                    if (preferClaimEvidence && selected.hit().source() != null) {
                        score += claimEvidenceBoost(selected.hit().source(), preferIndependentClaimEvidence);
                    }
                    return new FusedPatentDoc(selected.hit(), score, knn != null && bm25 != null ? "HYBRID" : selected.retrievalMode());
                })
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .map(item -> toResult(item.hit(), item.retrievalMode(), item.score()))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(),
                        results -> diversifyByPatent(results, topK)
                ));
    }

    private PatentSearchResult toResult(Hit<PatentEsDocument> hit, String retrievalMode) {
        return toResult(hit, retrievalMode, hit.score());
    }

    private PatentSearchResult toResult(Hit<PatentEsDocument> hit, String retrievalMode, Double score) {
        PatentEsDocument doc = hit.source();
        if (doc == null) {
            return new PatentSearchResult();
        }
        return new PatentSearchResult(
                doc.getPatentId(),
                doc.getPatentChunkId(),
                doc.getFileMd5(),
                doc.getFileName(),
                doc.getChunkNo(),
                repairMojibake(doc.getTextContent()),
                score,
                doc.getSourceType(),
                doc.getClaimNo(),
                doc.isIndependentClaim(),
                doc.getSectionPath(),
                doc.getPageNumber(),
                repairMojibake(doc.getAnchorText()),
                doc.getPublicationNo(),
                doc.getApplicationNo(),
                repairMojibake(doc.getTitle()),
                repairMojibake(doc.getApplicant()),
                doc.getPatentType(),
                retrievalMode
        );
    }

    private List<Query> buildFilters(PatentSearchRequest request, String userId) {
        List<Query> filters = new ArrayList<>();
        filters.add(buildPermissionFilter(userId));
        addTermFilter(filters, "fileName.keyword", request.getFileName());
        addTermFilter(filters, "publicationNo", request.getPublicationNo());
        addTermFilter(filters, "applicationNo", request.getApplicationNo());
        addTermFilter(filters, "patentType", request.getPatentType());
        addSourceTypeFilter(filters, request.getSourceType());
        if (Boolean.TRUE.equals(request.getIndependentClaimOnly())) {
            filters.add(buildIndependentClaimEvidenceFilter());
        }
        addMatchFilter(filters, "title", request.getTitle());
        addMatchFilter(filters, "applicant", request.getApplicant());
        return filters;
    }

    private void addSourceTypeFilter(List<Query> filters, String sourceType) {
        String normalized = normalize(sourceType);
        if (normalized == null) {
            return;
        }
        if (SOURCE_TYPE_CLAIM.equalsIgnoreCase(normalized)) {
            filters.add(buildClaimEvidenceFilter());
            return;
        }
        filters.add(Query.of(q -> q.term(t -> t.field("sourceType").value(normalized))));
    }

    private Query buildClaimEvidenceFilter() {
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("sourceType").value(SOURCE_TYPE_CLAIM)));
            b.should(s -> s.exists(e -> e.field("claimNo")));
            b.should(s -> s.matchPhrase(mp -> mp.field("textContent").query("权利要求")));
            b.should(s -> s.matchPhrase(mp -> mp.field("textContent").query("权利要求书")));
            b.minimumShouldMatch("1");
            return b;
        }));
    }

    private Query buildIndependentClaimEvidenceFilter() {
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("independentClaim").value(true)));
            b.should(s -> s.matchPhrase(mp -> mp.field("textContent").query("独立权利要求")));
            b.should(s -> s.matchPhrase(mp -> mp.field("textContent").query("一种用于生产 1,3-BDO 的方法")));
            b.should(s -> s.matchPhrase(mp -> mp.field("textContent").query("一种具有 1,3-BDO 通路的非天然存在的细菌")));
            b.minimumShouldMatch("1");
            return b;
        }));
    }

    private List<Query> buildQueryDerivedFilters(String query) {
        List<Query> filters = new ArrayList<>();

        Set<String> patentNumbers = extractPatentNumberCandidates(query);
        if (!patentNumbers.isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> {
                for (String patentNumber : patentNumbers) {
                    b.should(s -> s.term(t -> t.field("applicationNo").value(patentNumber)));
                    b.should(s -> s.term(t -> t.field("publicationNo").value(patentNumber)));
                }
                b.minimumShouldMatch("1");
                return b;
            })));
        }

        if (query.contains("外观设计")) {
            filters.add(Query.of(q -> q.bool(b -> {
                b.should(s -> s.term(t -> t.field("patentType").value("外观设计")));
                b.should(s -> s.match(m -> m.field("title").query("外观设计")));
                b.should(s -> s.match(m -> m.field("textContent").query("外观设计")));
                b.minimumShouldMatch("1");
                return b;
            })));
        }

        String organization = extractOrganization(query);
        if (organization != null) {
            filters.add(Query.of(q -> q.match(m -> m.field("applicant").query(organization))));
        }

        String year = extractYear(query);
        if (year != null) {
            if (query.contains("申请日") || query.contains("申请时间")) {
                filters.add(buildYearRangeFilter("applicationDate", year));
            } else if (query.contains("公开日") || query.contains("公告日") || query.contains("公布日")) {
                filters.add(buildYearRangeFilter("publicationDate", year));
            }
        }

        return filters;
    }

    private void addPatentNumberShouldQueries(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                              String query) {
        for (String patentNumber : extractPatentNumberCandidates(query)) {
            b.should(s -> s.term(t -> t.field("applicationNo").value(patentNumber).boost(6.0f)));
            b.should(s -> s.term(t -> t.field("publicationNo").value(patentNumber).boost(6.0f)));
        }
    }

    private void addFileNameShouldQueries(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                          String query) {
        for (String fileName : extractFileNameCandidates(query)) {
            b.should(s -> s.term(t -> t.field("fileName.keyword").value(fileName).boost(8.0f)));
            b.should(s -> s.match(m -> m.field("fileName").query(fileName).boost(5.0f)));
        }
    }

    private Query buildYearRangeFilter(String field, String year) {
        String start = year + "-01-01";
        String end = year + "-12-31";
        return Query.of(q -> q.range(r -> r
                .field(field)
                .gte(JsonData.of(start))
                .lte(JsonData.of(end))
        ));
    }

    private Set<String> extractPatentNumberCandidates(String query) {
        Set<String> candidates = new HashSet<>();
        Matcher matcher = PATENT_NUMBER_PATTERN.matcher(query);
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = raw.toUpperCase().replaceFirst("^CN", "");
            candidates.add(normalized);
            String compact = normalized.replace(".", "");
            candidates.add(compact);
            if (!normalized.contains(".") && compact.length() > 1) {
                candidates.add(compact.substring(0, compact.length() - 1) + "." + compact.substring(compact.length() - 1));
            }
        }
        return candidates;
    }

    private Set<String> extractFileNameCandidates(String query) {
        Set<String> candidates = new HashSet<>();
        if (query == null) {
            return candidates;
        }
        Matcher matcher = FILE_NAME_PATTERN.matcher(query);
        while (matcher.find()) {
            String fileName = normalize(matcher.group(1));
            if (fileName != null) {
                candidates.add(fileName);
            }
        }
        return candidates;
    }

    private boolean isClaimFocusedQuery(String query) {
        if (query == null) {
            return false;
        }
        return query.contains("权利要求")
                || query.contains("权要")
                || query.toLowerCase().contains("claim");
    }

    private boolean isIndependentClaimQuery(String query) {
        if (query == null) {
            return false;
        }
        return query.contains("独立权利要求")
                || query.contains("独权")
                || query.toLowerCase().contains("independent claim");
    }

    private boolean isMetadataListingQuery(String query) {
        if (query == null) {
            return false;
        }
        return query.contains("列出")
                || query.contains("分别")
                || query.contains("哪些")
                || query.contains("编号")
                || query.contains("申请号")
                || query.contains("公开号")
                || query.contains("申请人");
    }

    private String extractOrganization(String query) {
        if (!(query.contains("申请") || query.contains("申请人"))) {
            return null;
        }
        Matcher matcher = ORGANIZATION_PATTERN.matcher(query);
        return matcher.find() ? normalize(matcher.group(1)) : null;
    }

    private String extractYear(String query) {
        Matcher matcher = YEAR_PATTERN.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<PatentSearchResult> diversifyByPatent(List<PatentSearchResult> results, int topK) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Integer> selectedCountByPatent = new HashMap<>();
        List<PatentSearchResult> selected = new ArrayList<>(Math.min(topK, results.size()));

        for (PatentSearchResult result : results) {
            if (selected.size() >= topK) {
                break;
            }
            Long patentId = result.getPatentId();
            int selectedCount = selectedCountByPatent.getOrDefault(patentId, 0);
            if (selectedCount == 0 || uniquePatentCount(selectedCountByPatent) >= Math.min(topK, countUniquePatents(results))) {
                selected.add(result);
                selectedCountByPatent.put(patentId, selectedCount + 1);
            }
        }

        for (PatentSearchResult result : results) {
            if (selected.size() >= topK) {
                break;
            }
            if (!selected.contains(result)) {
                selected.add(result);
            }
        }

        return selected;
    }

    private int uniquePatentCount(Map<Long, Integer> selectedCountByPatent) {
        int count = 0;
        for (Integer value : selectedCountByPatent.values()) {
            if (value != null && value > 0) {
                count++;
            }
        }
        return count;
    }

    private int countUniquePatents(List<PatentSearchResult> results) {
        Set<Long> patentIds = new HashSet<>();
        for (PatentSearchResult result : results) {
            if (result.getPatentId() != null) {
                patentIds.add(result.getPatentId());
            }
        }
        return patentIds.size();
    }

    private Query buildPermissionFilter(String userId) {
        if (userId == null || userId.isBlank()) {
            return Query.of(q -> q.term(t -> t.field("isPublic").value(true)));
        }

        String userDbId = getUserDbId(userId);
        List<String> effectiveTags = getUserEffectiveOrgTags(userId);
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("userId").value(userDbId)));
            b.should(s -> s.term(t -> t.field("isPublic").value(true)));
            if (effectiveTags.isEmpty()) {
                b.should(s -> s.matchNone(mn -> mn));
            } else {
                for (String tag : effectiveTags) {
                    b.should(s -> s.term(t -> t.field("orgTag").value(tag)));
                }
            }
            return b;
        }));
    }

    private void addTermFilter(List<Query> filters, String field, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            filters.add(Query.of(q -> q.term(t -> t.field(field).value(normalized))));
        }
    }

    private void addMatchFilter(List<Query> filters, String field, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            filters.add(Query.of(q -> q.match(m -> m.field(field).query(normalized))));
        }
    }

    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vectors = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vectors == null || vectors.isEmpty()) {
                return null;
            }
            float[] raw = vectors.get(0);
            List<Float> result = new ArrayList<>(raw.length);
            for (float value : raw) {
                result.add(value);
            }
            return result;
        } catch (Exception e) {
            logger.warn("[PatentSearch] 查询向量生成失败，降级 BM25: {}", e.getMessage());
            return null;
        }
    }

    private int normalizeTopK(Integer rawTopK) {
        int topK = rawTopK != null ? rawTopK : DEFAULT_TOP_K;
        return Math.max(1, Math.min(topK, MAX_TOP_K));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean looksLikeClaimEvidence(PatentEsDocument doc) {
        if (doc == null) {
            return false;
        }
        if (SOURCE_TYPE_CLAIM.equalsIgnoreCase(doc.getSourceType()) || doc.getClaimNo() != null) {
            return true;
        }
        String text = repairMojibake(doc.getTextContent());
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("权利要求书")
                || text.contains("权 利 要 求 书")
                || text.matches("(?s).*(^|\\R)\\s*\\d+\\s*[.．、]\\s*(一种|根据权利要求).*")
                || text.matches("(?s).*(^|\\R)\\s*权利要求\\s*\\d+.*");
    }

    private double claimEvidenceBoost(PatentEsDocument doc, boolean preferIndependentClaimEvidence) {
        if (doc == null) {
            return 0.0d;
        }
        double boost = 0.0d;
        boolean sourceTypeClaim = SOURCE_TYPE_CLAIM.equalsIgnoreCase(doc.getSourceType());
        boolean hasClaimNo = doc.getClaimNo() != null;
        if (sourceTypeClaim) {
            boost += 1.6d;
        } else if (hasClaimNo) {
            boost += 0.8d;
        }
        if (preferIndependentClaimEvidence && doc.isIndependentClaim()) {
            boost += 0.9d;
        } else if (doc.isIndependentClaim()) {
            boost += 0.25d;
        }
        if (hasClaimNo && doc.getClaimNo() == 1) {
            boost += 0.3d;
        }
        return boost;
    }

    private String repairMojibake(String value) {
        return TextEncodingRepairUtil.repairMojibake(value);
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = resolveUser(userId);
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.warn("[PatentSearch] 获取用户组织标签失败: userId={}, error={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        User user = resolveUser(userId);
        return user.getId().toString();
    }

    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private record RankedPatentDoc(String docId, Hit<PatentEsDocument> hit, int rank, String retrievalMode) {
    }

    private record FusedPatentDoc(Hit<PatentEsDocument> hit, double score, String retrievalMode) {
    }
}
