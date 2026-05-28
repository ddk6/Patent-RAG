package com.yizhaoqi.smartpai.service.patent;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.regex.Pattern;

@Service
public class PatentEmbeddingEstimateService {

    private static final int APPROX_TOKENS_PER_PATENT_CHUNK = 1800;
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern LATIN_WORD_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    public EmbeddingEstimate estimate(InputStream inputStream) throws Exception {
        try (PDDocument document = PDDocument.load(inputStream)) {
            String text = new PDFTextStripper().getText(document);
            long estimatedTokens = estimateTokens(text);
            int estimatedChunkCount = estimateChunkCount(estimatedTokens, document.getNumberOfPages());
            return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
        }
    }

    private long estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        long chineseChars = CJK_PATTERN.matcher(text).results().count();
        long latinWords = LATIN_WORD_PATTERN.matcher(text).results().count();
        return chineseChars + Math.round(latinWords * 1.5d);
    }

    private int estimateChunkCount(long estimatedTokens, int pageCount) {
        if (estimatedTokens <= 0) {
            return Math.max(1, pageCount);
        }
        int byTokens = (int) Math.ceil((double) estimatedTokens / APPROX_TOKENS_PER_PATENT_CHUNK);
        return Math.max(1, Math.max(byTokens, Math.min(pageCount, 8)));
    }

    public record EmbeddingEstimate(long estimatedTokens, int estimatedChunkCount) {
    }
}
