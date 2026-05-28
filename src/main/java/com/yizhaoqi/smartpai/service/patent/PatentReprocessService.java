package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

@Service
public class PatentReprocessService {

    public static final String STATUS_PATENT_RETRY_QUEUED = "PATENT_RETRY_QUEUED";

    private static final Logger logger = LoggerFactory.getLogger(PatentReprocessService.class);
    private static final Set<String> ACTIVE_PARSE_STATUSES = Set.of(
            "PROCESSING",
            "VECTORIZING",
            "DIRECT_STRUCTURING",
            "PATENT_STRUCTURING",
            "PATENT_VECTORIZING",
            STATUS_PATENT_RETRY_QUEUED
    );

    private final FileUploadRepository fileUploadRepository;
    private final UploadService uploadService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaConfig kafkaConfig;

    public PatentReprocessService(FileUploadRepository fileUploadRepository,
                                  UploadService uploadService,
                                  KafkaTemplate<String, Object> kafkaTemplate,
                                  KafkaConfig kafkaConfig) {
        this.fileUploadRepository = fileUploadRepository;
        this.uploadService = uploadService;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaConfig = kafkaConfig;
    }

    public PatentReprocessResult enqueuePatentReprocess(String fileMd5,
                                                        String requesterId,
                                                        String requesterRole,
                                                        boolean force) {
        FileUpload fileUpload = findTargetFile(fileMd5, requesterId, requesterRole)
                .orElseThrow(() -> new CustomException("文件不存在或无权限访问", HttpStatus.NOT_FOUND));

        validateReprocessable(fileUpload, force);

        String objectUrl;
        try {
            objectUrl = uploadService.generateMergedObjectUrl(fileUpload.getFileMd5());
        } catch (Exception e) {
            throw new CustomException("无法生成待解析文件访问地址: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String previousDocumentType = normalizeDocumentType(fileUpload.getDocumentType());
        String previousParseStatus = fileUpload.getParseStatus();
        try {
            fileUpload.setDocumentType(FileUpload.DOCUMENT_TYPE_PATENT);
            fileUpload.setParseMethod("PATENT");
            fileUpload.setParseStatus(STATUS_PATENT_RETRY_QUEUED);
            fileUpload.setParsedAt(null);
            fileUploadRepository.save(fileUpload);

            FileProcessingTask task = new FileProcessingTask(
                    fileUpload.getFileMd5(),
                    objectUrl,
                    fileUpload.getFileName(),
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic()
            );
            kafkaTemplate.executeInTransaction(operations -> {
                operations.send(kafkaConfig.getFileProcessingTopic(), task);
                return true;
            });

            logger.info("[Patent] 已提交专利链路重试/补偿任务: fileMd5={}, ownerUserId={}, requesterId={}, force={}",
                    fileUpload.getFileMd5(), fileUpload.getUserId(), requesterId, force);
            return new PatentReprocessResult(
                    fileUpload.getFileMd5(),
                    fileUpload.getFileName(),
                    fileUpload.getUserId(),
                    previousDocumentType,
                    FileUpload.DOCUMENT_TYPE_PATENT,
                    previousParseStatus,
                    STATUS_PATENT_RETRY_QUEUED,
                    force,
                    LocalDateTime.now()
            );
        } catch (Exception e) {
            fileUpload.setParseStatus("FAILED");
            fileUpload.setParseMethod("PATENT");
            fileUpload.setDocumentType(FileUpload.DOCUMENT_TYPE_PATENT);
            fileUpload.setParsedAt(LocalDateTime.now());
            fileUploadRepository.save(fileUpload);
            throw new CustomException("提交专利重试任务失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Optional<FileUpload> findTargetFile(String fileMd5, String requesterId, String requesterRole) {
        if (fileMd5 == null || fileMd5.isBlank()) {
            return Optional.empty();
        }
        if (isAdmin(requesterRole)) {
            return fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        }
        return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, requesterId);
    }

    private void validateReprocessable(FileUpload fileUpload, boolean force) {
        if (fileUpload.getStatus() != FileUpload.STATUS_COMPLETED) {
            throw new CustomException("文件尚未合并完成，不能触发专利链路重试", HttpStatus.CONFLICT);
        }

        String parseStatus = normalizeStatus(fileUpload.getParseStatus());
        if (!force && ACTIVE_PARSE_STATUSES.contains(parseStatus)) {
            throw new CustomException("文件正在解析或已排队，请稍后重试；如确认需要重新提交，请使用 force=true", HttpStatus.CONFLICT);
        }

        boolean completedPatent = FileUpload.DOCUMENT_TYPE_PATENT.equals(normalizeDocumentType(fileUpload.getDocumentType()))
                && "COMPLETED".equals(parseStatus);
        if (!force && completedPatent) {
            throw new CustomException("该文件已经完成专利链路解析；如需强制重跑，请使用 force=true", HttpStatus.CONFLICT);
        }
    }

    private boolean isAdmin(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    private String normalizeDocumentType(String documentType) {
        return FileUpload.DOCUMENT_TYPE_PATENT.equalsIgnoreCase(documentType)
                ? FileUpload.DOCUMENT_TYPE_PATENT
                : FileUpload.DOCUMENT_TYPE_GENERAL;
    }

    public record PatentReprocessResult(
            String fileMd5,
            String fileName,
            String ownerUserId,
            String previousDocumentType,
            String documentType,
            String previousParseStatus,
            String parseStatus,
            boolean force,
            LocalDateTime queuedAt
    ) {
    }
}
