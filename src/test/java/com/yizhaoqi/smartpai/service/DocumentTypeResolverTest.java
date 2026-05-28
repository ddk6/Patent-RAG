package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentTypeResolverTest {

    private final DocumentTypeResolver resolver = new DocumentTypeResolver();

    @Test
    void patentDocumentTypeIsAccepted() {
        assertEquals(FileUpload.DOCUMENT_TYPE_PATENT, resolver.resolve("patent", "普通文档.pdf"));
    }

    @Test
    void defaultsToPatentForPatentOnlySystem() {
        assertEquals(FileUpload.DOCUMENT_TYPE_PATENT, resolver.resolve(null, "CN1122334455A.pdf"));
        assertEquals(FileUpload.DOCUMENT_TYPE_PATENT, resolver.resolve(null, "产品介绍.pdf"));
    }

    @Test
    void rejectsUnsupportedExplicitDocumentType() {
        assertThrows(CustomException.class, () -> resolver.resolve("contract", "test.pdf"));
    }
}
