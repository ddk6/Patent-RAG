package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 专利系统固定使用 PATENT 文档类型。
 */
@Service
public class DocumentTypeResolver {

    public String resolve(String explicitDocumentType, String fileName) {
        if (explicitDocumentType == null || explicitDocumentType.isBlank()) {
            return FileUpload.DOCUMENT_TYPE_PATENT;
        }
        return normalizeExplicit(explicitDocumentType);
    }

    public boolean hasExplicitDocumentType(String documentType) {
        return documentType != null && !documentType.isBlank();
    }

    private String normalizeExplicit(String rawDocumentType) {
        String normalized = rawDocumentType.trim().toUpperCase(Locale.ROOT);
        if (FileUpload.DOCUMENT_TYPE_PATENT.equals(normalized)) {
            return FileUpload.DOCUMENT_TYPE_PATENT;
        }
        throw new CustomException("不支持的 documentType: " + rawDocumentType
                + "，专利检索与审查系统仅支持 PATENT", HttpStatus.BAD_REQUEST);
    }
}
