package com.yizhaoqi.smartpai.service.patent.dto;

public record PatentParserRequest(
        String fileMd5,
        String fileName,
        String fullMd,
        String contentJson,
        String layoutJson,
        String fileUrl,
        String parserMode
) {
    public PatentParserRequest(String fileMd5,
                               String fileName,
                               String fullMd,
                               String contentJson,
                               String layoutJson) {
        this(fileMd5, fileName, fullMd, contentJson, layoutJson, null, "MINERU");
    }

    public static PatentParserRequest directPdf(String fileMd5, String fileName, String fileUrl) {
        return new PatentParserRequest(fileMd5, fileName, null, null, null, fileUrl, "DIRECT_PDF");
    }
}
