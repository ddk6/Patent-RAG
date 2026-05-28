package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.config.PatentParserProperties;
import com.yizhaoqi.smartpai.service.patent.dto.PatentParserResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative quality gate for the direct-PDF patent parser.
 * Direct parsing is only accepted when bibliographic, claim, and chunk signals
 * are strong enough to avoid trading correctness for latency.
 */
@Component
public class PatentParseQualityEvaluator {

    private final PatentParserProperties properties;

    public PatentParseQualityEvaluator(PatentParserProperties properties) {
        this.properties = properties;
    }

    public Evaluation evaluate(PatentParserResult result) {
        List<String> reasons = new ArrayList<>();
        if (result == null) {
            return new Evaluation(false, 0.0d, List.of("parser result is null"));
        }

        PatentParserResult.PatentMetadata metadata = result.getMetadata();
        int metadataSignals = countMetadataSignals(metadata);
        int claims = result.getClaims() != null ? result.getClaims().size() : 0;
        int chunks = result.getChunks() != null ? result.getChunks().size() : 0;
        int chunkTextChars = countChunkTextChars(result);

        if (metadataSignals < properties.getDirectMinMetadataSignals()) {
            reasons.add("metadata signals too weak: " + metadataSignals);
        }
        if (claims < properties.getDirectMinClaims()) {
            reasons.add("claims too few: " + claims);
        }
        if (chunks < properties.getDirectMinChunks()) {
            reasons.add("chunks too few: " + chunks);
        }
        if (chunkTextChars < properties.getDirectMinChunkTextChars()) {
            reasons.add("chunk text too short: " + chunkTextChars);
        }

        double score = score(result, metadataSignals, claims, chunks, chunkTextChars);
        if (score < properties.getDirectQualityThreshold()) {
            reasons.add("score below threshold: " + round(score));
        }

        return new Evaluation(reasons.isEmpty(), score, reasons);
    }

    private double score(PatentParserResult result,
                         int metadataSignals,
                         int claims,
                         int chunks,
                         int chunkTextChars) {
        double score = 0.0d;
        PatentParserResult.PatentMetadata metadata = result.getMetadata();

        score += Math.min(metadataSignals, 6) / 6.0d * 0.25d;
        score += claimScore(result) * 0.35d;
        score += structureScore(result, chunks, chunkTextChars) * 0.30d;
        score += warningScore(result) * 0.10d;

        if (metadata != null && hasValue(metadata.getApplicationNumber()) && hasValue(metadata.getPublicationNumber())) {
            score += 0.03d;
        }
        if (claims >= 5) {
            score += 0.03d;
        }

        return Math.min(1.0d, score);
    }

    private double claimScore(PatentParserResult result) {
        if (result.getClaims() == null || result.getClaims().isEmpty()) {
            return 0.0d;
        }

        double score = 0.30d;
        boolean hasIndependent = result.getClaims().stream().anyMatch(PatentParserResult.PatentClaimItem::isIndependent);
        if (hasIndependent) {
            score += 0.20d;
        }

        if (hasSaneClaimNumbers(result)) {
            score += 0.25d;
        }

        double averageLength = result.getClaims().stream()
                .map(PatentParserResult.PatentClaimItem::getText)
                .filter(this::hasValue)
                .mapToInt(String::length)
                .average()
                .orElse(0.0d);
        if (averageLength >= 80) {
            score += 0.15d;
        } else if (averageLength >= 40) {
            score += 0.08d;
        }

        boolean hasFeatureJson = result.getClaims().stream()
                .map(PatentParserResult.PatentClaimItem::getTechnicalFeaturesJson)
                .anyMatch(this::hasValue);
        if (hasFeatureJson) {
            score += 0.10d;
        }

        return Math.min(1.0d, score);
    }

    private boolean hasSaneClaimNumbers(PatentParserResult result) {
        Set<Integer> seen = new HashSet<>();
        int previous = 0;
        int ordered = 0;
        for (PatentParserResult.PatentClaimItem claim : result.getClaims()) {
            Integer claimNo = claim.getClaimNo();
            if (claimNo == null || claimNo <= 0 || !seen.add(claimNo)) {
                return false;
            }
            if (claimNo > previous) {
                ordered++;
            }
            previous = claimNo;
        }
        return seen.contains(1) && ordered >= Math.max(1, result.getClaims().size() - 1);
    }

    private double structureScore(PatentParserResult result, int chunks, int chunkTextChars) {
        double score = 0.0d;
        PatentParserResult.PatentMetadata metadata = result.getMetadata();
        if (metadata != null && hasValue(metadata.getAbstractText())) {
            score += 0.20d;
        }
        int sections = result.getSections() != null ? result.getSections().size() : 0;
        if (sections >= 2) {
            score += 0.25d;
        } else if (sections == 1) {
            score += 0.12d;
        }
        if (hasSourceType(result, "BIBLIOGRAPHIC")) {
            score += 0.10d;
        }
        if (hasSourceType(result, "CLAIM")) {
            score += 0.20d;
        }
        if (hasSourceType(result, "DESCRIPTION")) {
            score += 0.10d;
        }
        if (chunks >= properties.getDirectMinChunks() && chunkTextChars >= properties.getDirectMinChunkTextChars()) {
            score += 0.15d;
        }
        return Math.min(1.0d, score);
    }

    private double warningScore(PatentParserResult result) {
        int warnings = result.getWarnings() != null ? result.getWarnings().size() : 0;
        if (warnings == 0) {
            return 1.0d;
        }
        if (warnings == 1) {
            return 0.65d;
        }
        return 0.25d;
    }

    private boolean hasSourceType(PatentParserResult result, String sourceType) {
        return result.getChunks() != null
                && result.getChunks().stream().anyMatch(chunk -> sourceType.equals(chunk.getSourceType()));
    }

    private int countMetadataSignals(PatentParserResult.PatentMetadata metadata) {
        if (metadata == null) {
            return 0;
        }
        int count = 0;
        count += hasValue(metadata.getApplicationNumber()) ? 1 : 0;
        count += hasValue(metadata.getPublicationNumber()) ? 1 : 0;
        count += hasValue(metadata.getTitle()) ? 1 : 0;
        count += hasValue(metadata.getApplicant()) ? 1 : 0;
        count += hasValue(metadata.getPatentType()) ? 1 : 0;
        count += hasValue(metadata.getApplicationDate()) || hasValue(metadata.getPublicationDate()) ? 1 : 0;
        count += hasValue(metadata.getAbstractText()) ? 1 : 0;
        count += hasValue(metadata.getMainClaimText()) ? 1 : 0;
        return count;
    }

    private int countChunkTextChars(PatentParserResult result) {
        if (result.getChunks() == null) {
            return 0;
        }
        return result.getChunks().stream()
                .map(PatentParserResult.PatentChunkItem::getText)
                .filter(this::hasValue)
                .mapToInt(String::length)
                .sum();
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    public record Evaluation(boolean acceptable, double score, List<String> reasons) {
    }
}
