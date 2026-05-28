package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatentReprocessServiceTest {

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private UploadService uploadService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private KafkaConfig kafkaConfig;

    private PatentReprocessService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PatentReprocessService(fileUploadRepository, uploadService, kafkaTemplate, kafkaConfig);
    }

    @Test
    void queuesGeneralCompletedFileForPatentReprocess() throws Exception {
        FileUpload fileUpload = completedFile("md5", "1");
        fileUpload.setDocumentType(FileUpload.DOCUMENT_TYPE_GENERAL);
        fileUpload.setParseStatus("COMPLETED");

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(uploadService.generateMergedObjectUrl("md5")).thenReturn("https://example.com/merged/md5");
        when(kafkaConfig.getFileProcessingTopic()).thenReturn("file-processing");
        doReturn(true).when(kafkaTemplate).executeInTransaction(any());

        PatentReprocessService.PatentReprocessResult result =
                service.enqueuePatentReprocess("md5", "1", "USER", false);

        assertEquals(FileUpload.DOCUMENT_TYPE_GENERAL, result.previousDocumentType());
        assertEquals(FileUpload.DOCUMENT_TYPE_PATENT, result.documentType());
        assertEquals(PatentReprocessService.STATUS_PATENT_RETRY_QUEUED, result.parseStatus());
        assertEquals(FileUpload.DOCUMENT_TYPE_PATENT, fileUpload.getDocumentType());
        assertEquals("PATENT", fileUpload.getParseMethod());
        assertEquals(PatentReprocessService.STATUS_PATENT_RETRY_QUEUED, fileUpload.getParseStatus());
        verify(fileUploadRepository).save(fileUpload);
        verify(kafkaTemplate).executeInTransaction(any());
    }

    @Test
    void rejectsActivePatentReprocessWithoutForce() throws Exception {
        FileUpload fileUpload = completedFile("md5", "1");
        fileUpload.setParseStatus("PATENT_VECTORIZING");

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        CustomException exception = assertThrows(
                CustomException.class,
                () -> service.enqueuePatentReprocess("md5", "1", "USER", false)
        );

        assertEquals(409, exception.getStatus().value());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    @Test
    void rejectsCompletedPatentWithoutForce() throws Exception {
        FileUpload fileUpload = completedFile("md5", "1");
        fileUpload.setDocumentType(FileUpload.DOCUMENT_TYPE_PATENT);
        fileUpload.setParseStatus("COMPLETED");

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        CustomException exception = assertThrows(
                CustomException.class,
                () -> service.enqueuePatentReprocess("md5", "1", "USER", false)
        );

        assertEquals(409, exception.getStatus().value());
        verify(kafkaTemplate, never()).executeInTransaction(any());
    }

    private FileUpload completedFile(String fileMd5, String userId) {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5(fileMd5);
        fileUpload.setFileName("test.pdf");
        fileUpload.setUserId(userId);
        fileUpload.setOrgTag("TEAM_A");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);
        return fileUpload;
    }
}
