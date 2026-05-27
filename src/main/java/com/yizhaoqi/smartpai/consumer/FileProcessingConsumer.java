package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.config.MinerUProperties;
import com.yizhaoqi.smartpai.config.PatentParserProperties;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.MinerUParseResult;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.MinerUParseResultRepository;
import com.yizhaoqi.smartpai.service.MinerUService;
import com.yizhaoqi.smartpai.service.patent.PatentIngestionService;
import com.yizhaoqi.smartpai.service.patent.PatentParseQualityEvaluator;
import com.yizhaoqi.smartpai.service.patent.PatentParserClient;
import com.yizhaoqi.smartpai.service.patent.PatentVectorizationService;
import com.yizhaoqi.smartpai.service.patent.PatentVectorizationService.VectorizationUsageResult;
import com.yizhaoqi.smartpai.service.patent.dto.PatentParserRequest;
import com.yizhaoqi.smartpai.service.patent.dto.PatentParserResult;
import com.yizhaoqi.smartpai.model.patent.PatentDocument;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final FileUploadRepository fileUploadRepository;
    private final MinerUParseResultRepository mineruParseResultRepository;
    private final MinerUService minerUService;
    private final MinerUProperties minerUProperties;
    private final PatentParserProperties patentParserProperties;
    private final PatentIngestionService patentIngestionService;
    private final PatentParseQualityEvaluator patentParseQualityEvaluator;
    private final PatentParserClient patentParserClient;
    private final PatentVectorizationService patentVectorizationService;
    @Autowired
    private KafkaConfig kafkaConfig;


    public FileProcessingConsumer(
            FileUploadRepository fileUploadRepository,
            MinerUParseResultRepository mineruParseResultRepository,
            MinerUService minerUService,
            MinerUProperties minerUProperties,
            PatentParserProperties patentParserProperties,
            PatentIngestionService patentIngestionService,
            PatentParseQualityEvaluator patentParseQualityEvaluator,
            PatentParserClient patentParserClient,
            PatentVectorizationService patentVectorizationService
    ) {
        this.fileUploadRepository = fileUploadRepository;
        this.mineruParseResultRepository = mineruParseResultRepository;
        this.minerUService = minerUService;
        this.minerUProperties = minerUProperties;
        this.patentParserProperties = patentParserProperties;
        this.patentIngestionService = patentIngestionService;
        this.patentParseQualityEvaluator = patentParseQualityEvaluator;
        this.patentParserClient = patentParserClient;
        this.patentVectorizationService = patentVectorizationService;
    }

    // 处理专利文件解析任务：直提优先，MinerU 兜底，不再写通用知识库链路。
    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}",
                task.getUserId(), task.getOrgTag(), task.isPublic());

        if (isAlreadyCompleted(task)) {
            log.info("文件处理任务已完成，跳过重复 Kafka 消息: fileMd5={}, userId={}",
                    task.getFileMd5(), task.getUserId());
            return;
        }

        updateParseStatus(task, "PROCESSING", "PATENT");

        try {
            if (processPatentDirectParse(task)) {
                return;
            }
            if (!minerUProperties.isEnabled()) {
                throw new IllegalStateException("专利直提未通过质量门禁，且 MinerU 未启用，无法继续专利链路");
            }

            processWithMinerUPatent(task);
        } catch (Exception e) {
            log.error("[Patent] 专利解析失败: fileMd5={}", task.getFileMd5(), e);
            updateParseStatus(task, "FAILED", "PATENT");
            throw new RuntimeException("Patent document processing failed", e);
        }
    }

    /**
     * MinerU 专利兜底解析流程。
     */
    private void processWithMinerUPatent(FileProcessingTask task) throws Exception {
        log.info("[Patent] 开始 MinerU 专利兜底解析: fileMd5={}", task.getFileMd5());

        // 1. 下载文件到临时路径
        //如果文件是本地文件，直接返回文件流 然后调用MinerU API解析文件
        //如果文件是远程URL，使用HTTP GET请求下载文件
        //这里返回的是临时文件的路径
        Path filePath = downloadFileToTemp(task.getFilePath(), task.getFileMd5());
        log.info("[MinerU] 文件下载到临时路径: {}", filePath);

        try {
            // 2. 调用 MinerU API 解析 将文件名、文件路径、文件MD5值上传到MinerU服务器，等待解析完成，下载解析结果
            //这个解析结果是一个ZIP文件，包含解析后的JSON文件
            MinerUService.MinerUParseResult parseResult = minerUService.uploadAndParse(
                    filePath,
                    task.getFileName(),
                    task.getFileMd5()
            );

            // 3. 保存 MinerU 解析结果到 mineru_parse_result 表
            //这个表是MinerU解析结果的存储表 字段有：fileMd5, parser, status, createdAt, updatedAt
            //这个表存在数据库中
            saveMinerUResult(task.getFileMd5(), parseResult);

            processPatentParseResult(task, parseResult);
            log.info("[Patent] MinerU 专利兜底链路处理完成: fileMd5={}", task.getFileMd5());

        } finally {
            // 6. 清理临时文件
            //这里删除的是临时文件的路径
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.warn("[MinerU] 清理临时文件失败: {}", filePath);
            }
        }
    }

    /**
     * 保存 MinerU 解析结果到数据库
     */
    //这是保存MinerU解析结果的方法
    //根据文件MD5保存解析结果
    //为啥要保存解析结果？因为MinerU解析结果是一个JSON文件，包含解析后的文本内容、布局信息、元数据等
    //我们需要将这些信息保存到数据库中，方便后续的检索和分析
    private void saveMinerUResult(String fileMd5, MinerUService.MinerUParseResult parseResult) {
        MinerUParseResult entity = mineruParseResultRepository.findByFileMd5(fileMd5)
                .orElseGet(MinerUParseResult::new);
        entity.setFileMd5(fileMd5);
        entity.setFullMd(parseResult.getFullMd());
        entity.setContentJson(parseResult.getContentJson());
        entity.setLayoutJson(parseResult.getLayoutJson());
        entity.setMineruBatchId(parseResult.getMineruBatchId());
        entity.setParseStatus(parseResult.getParseStatus());
        entity.setParseError(parseResult.getParseError());
        mineruParseResultRepository.save(entity);
        log.info("[MinerU] 解析结果已保存: fileMd5={}", fileMd5);
        //把minerU的解析结果保存到数据库中 以便后续的分块和向量化
        //分块也是先从数据库中获取解析结果，再进行分块操作
        //分完块后，需要将分块结果保存到数据库中
        //然后再从数据库中获取分块结果，进行向量化操作
        //这个过程中用到了两次数据库操作，一次是保存解析结果，一次是保存分块结果
    }

    /**
     * 更新文件解析状态
     */
    //这是更新文件解析状态的方法
    //根据文件MD5更新解析状态和解析方法
    //如果解析状态为 COMPLETED 或 FAILED，更新解析时间
    //如果解析状态为 FAILED，更新解析错误信息
    private void updateParseStatus(FileProcessingTask task, String status, String parseMethod) {
        String fileMd5 = task != null ? task.getFileMd5() : null;
        String userId = task != null ? task.getUserId() : null;
        try {
            findLatestFileUpload(fileMd5, userId)
                    .ifPresent(fileUpload -> {
                        fileUpload.setParseStatus(status);
                        if (parseMethod != null) {
                            fileUpload.setParseMethod(parseMethod);
                        }
                        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                            fileUpload.setParsedAt(LocalDateTime.now());
                        }
                        fileUploadRepository.save(fileUpload);
                        log.debug("更新解析状态: fileMd5={}, status={}, method={}", fileMd5, status, parseMethod);
                    });
        } catch (Exception e) {
            log.warn("更新解析状态失败: fileMd5={}", fileMd5, e);
        }
    }

    private void processPatentParseResult(FileProcessingTask task, MinerUService.MinerUParseResult minerUParseResult) {
        FileUpload fileUpload = findLatestFileUpload(task.getFileMd5(), task.getUserId())
                .orElseThrow(() -> new IllegalStateException("文件记录不存在: " + task.getFileMd5()));
        PatentDocument patentDocument = null;

        try {
            updateParseStatus(task, "PATENT_STRUCTURING", "PATENT");
            PatentParserResult parserResult = patentParserClient.parse(new PatentParserRequest(
                    task.getFileMd5(),
                    task.getFileName(),
                    minerUParseResult.getFullMd(),
                    minerUParseResult.getContentJson(),
                    minerUParseResult.getLayoutJson()
            ));

            validatePatentParserResult(parserResult);

            patentDocument = patentIngestionService.begin(task, fileUpload);
            persistAndVectorizePatentResult(task, fileUpload, patentDocument, parserResult, "PATENT");
        } catch (Exception e) {
            if (patentDocument == null) {
                patentDocument = patentIngestionService.begin(task, fileUpload);
            }
            patentIngestionService.markFailed(patentDocument.getId(), e.getMessage());
            updateParseStatus(task, "FAILED", "PATENT");
            throw new RuntimeException("专利解析失败: " + e.getMessage(), e);
        }
    }

    private boolean processPatentDirectParse(FileProcessingTask task) {
        if (!patentParserProperties.isDirectEnabled()) {
            return false;
        }

        FileUpload fileUpload = findLatestFileUpload(task.getFileMd5(), task.getUserId())
                .orElseThrow(() -> new IllegalStateException("文件记录不存在: " + task.getFileMd5()));

        log.info("[Patent] 尝试专利直提快路径: fileMd5={}, fileName={}", task.getFileMd5(), task.getFileName());
        PatentDocument patentDocument = null;
        boolean acceptedDirectResult = false;
        try {
            updateParseStatus(task, "DIRECT_STRUCTURING", "PATENT_DIRECT");
            PatentParserResult parserResult = patentParserClient.parse(PatentParserRequest.directPdf(
                    task.getFileMd5(),
                    task.getFileName(),
                    task.getFilePath()
            ));
            PatentParseQualityEvaluator.Evaluation evaluation = patentParseQualityEvaluator.evaluate(parserResult);
            if (!evaluation.acceptable()) {
                updateParseStatus(task, "DIRECT_FALLBACK", "PATENT_DIRECT");
                log.info("[Patent] 专利直提质量不足，回退 MinerU: fileMd5={}, score={}, reasons={}",
                        task.getFileMd5(), evaluation.score(), evaluation.reasons());
                return false;
            }

            validatePatentParserResult(parserResult);
            acceptedDirectResult = true;
            patentDocument = patentIngestionService.begin(task, fileUpload);
            persistAndVectorizePatentResult(task, fileUpload, patentDocument, parserResult, "PATENT_DIRECT");
            log.info("[Patent] 专利直提快路径完成: fileMd5={}, score={}", task.getFileMd5(), evaluation.score());
            return true;
        } catch (Exception e) {
            if (acceptedDirectResult) {
                if (patentDocument != null) {
                    patentIngestionService.markFailed(patentDocument.getId(), e.getMessage());
                }
                updateParseStatus(task, "FAILED", "PATENT_DIRECT");
                throw new RuntimeException("专利直提结果已通过质量门禁，但后续处理失败: " + e.getMessage(), e);
            }
            if (patentDocument != null) {
                patentIngestionService.markFailed(patentDocument.getId(), e.getMessage());
            }
            updateParseStatus(task, "DIRECT_FALLBACK", "PATENT_DIRECT");
            log.warn("[Patent] 专利直提失败，回退 MinerU: fileMd5={}, error={}",
                    task.getFileMd5(), e.getMessage(), e);
            return false;
        }
    }

    private void persistAndVectorizePatentResult(FileProcessingTask task,
                                                 FileUpload fileUpload,
                                                 PatentDocument patentDocument,
                                                 PatentParserResult parserResult,
                                                 String parseMethod) {
        patentDocument = patentIngestionService.saveParserResult(patentDocument.getId(), parserResult);

        fileUpload.setDocumentType(FileUpload.DOCUMENT_TYPE_PATENT);
        fileUploadRepository.save(fileUpload);

        updateParseStatus(task, "PATENT_VECTORIZING", parseMethod);
        VectorizationUsageResult vectorizationResult = patentVectorizationService.vectorizeWithUsage(
                patentDocument.getId(),
                task.getUserId()
        );
        updateActualEmbeddingUsage(task, vectorizationResult);

        updateParseStatus(task, "COMPLETED", parseMethod);
    }

    private void validatePatentParserResult(PatentParserResult parserResult) {
        if (parserResult == null) {
            throw new IllegalStateException("专利解析服务返回空结果");
        }

        boolean hasClaims = parserResult.getClaims() != null && !parserResult.getClaims().isEmpty();
        boolean hasSections = parserResult.getSections() != null && !parserResult.getSections().isEmpty();
        boolean hasChunks = parserResult.getChunks() != null && !parserResult.getChunks().isEmpty();
        boolean hasMetadata = parserResult.getMetadata() != null
                && (notBlank(parserResult.getMetadata().getApplicationNumber())
                || notBlank(parserResult.getMetadata().getPublicationNumber())
                || notBlank(parserResult.getMetadata().getTitle()));

        if (!hasChunks || (!hasClaims && !hasSections && !hasMetadata)) {
            throw new IllegalStateException("专利解析结果缺少有效结构化内容");
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private Optional<FileUpload> findLatestFileUpload(String fileMd5, String userId) {
        if (fileMd5 == null) {
            return Optional.empty();
        }
        if (userId != null) {
            Optional<FileUpload> upload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
            if (upload.isPresent()) {
                return upload;
            }
        }
        return fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
    }

    private boolean isAlreadyCompleted(FileProcessingTask task) {
        if (task == null || task.getFileMd5() == null || task.getUserId() == null) {
            return false;
        }
        return fileUploadRepository
                .findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(task.getFileMd5(), task.getUserId())
                .map(fileUpload -> "COMPLETED".equalsIgnoreCase(fileUpload.getParseStatus()))
                .orElse(false);
    }

    /**
     * 下载文件到临时路径（用于 MinerU）
     */
    //minerU比Tika解析文件多了一个临时路径的创建
    //临时路径是为了在MinerU API解析文件时，能够直接上传文件到MinerU服务器

    //这里返回的是什么？
    //返回的是临时路径的临时文件路径
    private Path downloadFileToTemp(String filePath, String fileMd5) throws Exception {
        Path tempFile = Files.createTempFile("mineru_input_", "_" + fileMd5);

            //从存储系统minio下载文件到临时路径
        //
        try (InputStream in = downloadFileFromStorage(filePath);
             OutputStream out = Files.newOutputStream(tempFile)) {
            if (in == null) {
                throw new IOException("无法下载文件: " + filePath);
            }
            in.transferTo(out);
        }
        //返回临时文件路径
        return tempFile;
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    //这是从存储系统minio下载文件的方法
    //如果文件是本地文件，直接返回文件流
    //如果文件是远程URL，使用HTTP GET请求下载文件
    //tika解析器直接从文件系统读取文件，不需要临时路径 因此会直接调用downloadFileFromStorage方法返回文件流
    //但是minerU解析器需要临时路径 因此会先调用downloadFileToTemp方法下载文件到临时文件
    //然后再从临时文件读取文件流
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }

    //这是更新实际Embedding用量的方法
    //如果任务或向量结果为空，直接返回
    //如果文件记录不存在，也返回
    private void updateActualEmbeddingUsage(
            FileProcessingTask task,
            VectorizationUsageResult vectorizationResult
    ) {
        if (task == null || vectorizationResult == null || task.getFileMd5() == null || task.getUserId() == null) {
            return;
        }

        FileUpload fileUpload = fileUploadRepository
                .findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(task.getFileMd5(), task.getUserId())
                .orElse(null);
        if (fileUpload == null) {
            log.warn("回写实际 Embedding 用量失败，未找到文件记录: fileMd5={}, userId={}", task.getFileMd5(), task.getUserId());
            return;
        }

        fileUpload.setActualEmbeddingTokens((long) vectorizationResult.actualEmbeddingTokens());
        fileUpload.setActualChunkCount(vectorizationResult.actualChunkCount());
        fileUploadRepository.save(fileUpload);
    }
}
