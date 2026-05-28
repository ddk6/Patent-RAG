package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.config.PatentParserProperties;
import com.yizhaoqi.smartpai.model.patent.PatentDocument;
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
            ComponentScores scores = new ComponentScores(0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
            return new Evaluation(false, 0.0d, List.of("parser result is null"), scores,
                    PatentDocument.QUALITY_NEEDS_REVIEW);
        }

        PatentParserResult.PatentMetadata metadata = result.getMetadata();
        int metadataSignals = countMetadataSignals(metadata);
        int claims = result.getClaims() != null ? result.getClaims().size() : 0;
        int chunks = result.getChunks() != null ? result.getChunks().size() : 0;
        int chunkTextChars = countChunkTextChars(result);
        ComponentScores scores = componentScores(result, metadataSignals, chunks, chunkTextChars);

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

        double score = scores.overallScore();
        if (score < properties.getDirectQualityThreshold()) {
            reasons.add("score below threshold: " + round(score));
        }

        return new Evaluation(reasons.isEmpty(), score, reasons, scores, qualityLevel(score, scores));
    }

    private ComponentScores componentScores(PatentParserResult result,
                                            int metadataSignals,
                                            int chunks,
                                            int chunkTextChars) {
        double metadataScore = metadataScore(metadataSignals);
        double claimScore = claimScore(result);
        double sectionScore = sectionScore(result);
        double chunkScore = chunkScore(result, chunks, chunkTextChars);
        double ocrScore = warningScore(result);
        double overallScore = overallScore(metadataScore, claimScore, sectionScore, chunkScore, ocrScore, result);

        return new ComponentScores(
                metadataScore,
                claimScore,
                sectionScore,
                chunkScore,
                ocrScore,
                overallScore
        );
    }

    private double overallScore(double metadataScore,
                                double claimScore,
                                double sectionScore,
                                double chunkScore,
                                double ocrScore,
                                PatentParserResult result) {
        PatentParserResult.PatentMetadata metadata = result.getMetadata();

        double score = 0.0d;
        score += metadataScore * 0.25d;
        score += claimScore * 0.35d;
        score += sectionScore * 0.20d;
        score += chunkScore * 0.10d;
        score += ocrScore * 0.10d;

        if (metadata != null && hasValue(metadata.getApplicationNumber()) && hasValue(metadata.getPublicationNumber())) {
            score += 0.03d;
        }
        int claims = result.getClaims() != null ? result.getClaims().size() : 0;
        if (claims >= 5) {
            score += 0.03d;
        }

        return Math.min(1.0d, score);
    }

    private double metadataScore(int metadataSignals) {
        return Math.min(metadataSignals, 6) / 6.0d;
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

    private double sectionScore(PatentParserResult result) {
        double score = 0.0d;
        PatentParserResult.PatentMetadata metadata = result.getMetadata();
        if (metadata != null && hasValue(metadata.getAbstractText())) {
            score += 0.25d;
        }
        int sections = result.getSections() != null ? result.getSections().size() : 0;
        if (sections >= 2) {
            score += 0.45d;
        } else if (sections == 1) {
            score += 0.22d;
        }
        if (hasSourceType(result, "BIBLIOGRAPHIC")) {
            score += 0.10d;
        }
        if (hasSourceType(result, "DESCRIPTION")) {
            score += 0.20d;
        }
        return Math.min(1.0d, score);
    }

    private double chunkScore(PatentParserResult result, int chunks, int chunkTextChars) {
        double score = 0.0d;
        if (chunks >= properties.getDirectMinChunks()) {
            score += 0.40d;
        } else if (chunks > 0) {
            score += 0.20d;
        }
        if (chunkTextChars >= properties.getDirectMinChunkTextChars()) {
            score += 0.40d;
        } else if (chunkTextChars >= properties.getDirectMinChunkTextChars() / 2) {
            score += 0.20d;
        }
        if (hasSourceType(result, "CLAIM")) {
            score += 0.20d;
        }
        return Math.min(1.0d, score);
    }

    private String qualityLevel(double score, ComponentScores scores) {
        if (scores.claimScore() == 0.0d || score < 0.55d) {
            return PatentDocument.QUALITY_NEEDS_REVIEW;
        }
        if (score >= 0.85d) {
            return PatentDocument.QUALITY_EXCELLENT;
        }
        if (score >= 0.70d) {
            return PatentDocument.QUALITY_USABLE;
        }
        return PatentDocument.QUALITY_NEEDS_REVIEW;
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

    public record ComponentScores(
            double metadataScore,
            double claimScore,
            double sectionScore,
            double chunkScore,
            double ocrScore,
            double overallScore
    ) {
    }

    public record Evaluation(
            boolean acceptable,
            double score,
            List<String> reasons,
            ComponentScores scores,
            String qualityLevel
    ) {
    }
}
