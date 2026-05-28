package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.service.MinerUService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Conservative patent document detector.
 * It only auto-routes documents with multiple strong CN patent signals.
 */
@Component
public class PatentDocumentDetector {

    private static final int MAX_SCAN_CHARS = 12000;

    private static final List<String> STRONG_SIGNALS = List.of(
            "权利要求书",
            "说明书",
            "中华人民共和国国家知识产权局"
    );

    private static final List<String> METADATA_SIGNALS = List.of(
            "申请号",
            "申请公布号",
            "授权公告号",
            "公开号",
            "公告号",
            "申请人",
            "发明人",
            "专利代理机构",
            "int.cl"
    );

    public boolean isPatent(MinerUService.MinerUParseResult parseResult, String fileName) {
        String scanText = buildScanText(parseResult, fileName);
        if (scanText.isBlank()) {
            return false;
        }

        String normalized = scanText.toLowerCase(Locale.ROOT);
        int strongHits = countHits(normalized, STRONG_SIGNALS);
        int metadataHits = countHits(normalized, METADATA_SIGNALS);

        boolean hasPatentTitle = normalized.contains("发明专利")
                || normalized.contains("实用新型专利")
                || normalized.contains("外观设计专利");

        return strongHits >= 2 && (metadataHits >= 2 || hasPatentTitle);
    }

    private String buildScanText(MinerUService.MinerUParseResult parseResult, String fileName) {
        StringBuilder text = new StringBuilder();
        if (fileName != null) {
            text.append(fileName).append('\n');
        }
        if (parseResult != null) {
            append(text, parseResult.getFullMd());
            append(text, parseResult.getContentJson());
        }
        if (text.length() > MAX_SCAN_CHARS) {
            return text.substring(0, MAX_SCAN_CHARS);
        }
        return text.toString();
    }

    private void append(StringBuilder target, String value) {
        if (value == null || value.isBlank() || target.length() >= MAX_SCAN_CHARS) {
            return;
        }
        int remaining = MAX_SCAN_CHARS - target.length();
        target.append(value, 0, Math.min(value.length(), remaining)).append('\n');
    }

    private int countHits(String text, List<String> signals) {
        int hits = 0;
        for (String signal : signals) {
            if (text.contains(signal.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }
}
