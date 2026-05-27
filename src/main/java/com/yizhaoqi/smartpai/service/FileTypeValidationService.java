package com.yizhaoqi.smartpai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 文件类型验证服务
 * 用于验证上传的文件类型是否支持解析和向量化
 */
@Service
public class FileTypeValidationService {

    private static final Logger logger = LoggerFactory.getLogger(FileTypeValidationService.class);

    /**
     * 专利链路仅支持 PDF，复杂版式由 MinerU 兜底。
     */
    private static final Set<String> SUPPORTED_DOCUMENT_EXTENSIONS = Set.of("pdf");

    /**
     * 不支持的文件类型扩展名（无法有效解析文本内容的文件类型）
     */
    private static final Set<String> UNSUPPORTED_EXTENSIONS = Set.of(
            // 图片文件
            "jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "tiff", "ico", "psd",
            
            // 音频文件
            "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a",
            
            // 视频文件
            "mp4", "avi", "mov", "wmv", "flv", "mkv", "webm", "m4v", "3gp",
            
            // 压缩包
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            
            // 可执行文件
            "exe", "msi", "dmg", "pkg", "deb", "rpm",
            
            // 字体文件
            "ttf", "otf", "woff", "woff2", "eot",
            
            // CAD文件
            "dwg", "dxf", "step", "iges",
            
            // 数据库文件
            "db", "sqlite", "mdb", "accdb",
            
            // 其他二进制文件
            "bin", "dat", "iso", "img"
    );

    /**
     * 验证文件类型是否支持
     *
     * @param fileName 文件名
     * @return 验证结果
     */
    public FileTypeValidationResult validateFileType(String fileName) {
        logger.debug("开始验证文件类型: fileName={}", fileName);
        
        if (fileName == null || fileName.trim().isEmpty()) {
            logger.warn("文件名为空或null");
            return new FileTypeValidationResult(false, "文件名不能为空", "unknown", null);
        }

        // 提取文件扩展名
        String extension = extractFileExtension(fileName);
        if (extension == null) {
            logger.warn("无法提取文件扩展名: fileName={}", fileName);
            return new FileTypeValidationResult(false, "文件必须有扩展名", "unknown", null);
        }

        String fileType = getFileTypeDescription(extension);
        logger.debug("文件类型识别结果: fileName={}, extension={}, fileType={}", fileName, extension, fileType);

        // 检查是否为支持的文档类型
        if (SUPPORTED_DOCUMENT_EXTENSIONS.contains(extension)) {
            logger.info("文件类型验证通过: fileName={}, extension={}, fileType={}", fileName, extension, fileType);
            return new FileTypeValidationResult(true, "支持的文件类型", fileType, extension);
        }

        // 检查是否为明确不支持的类型
        if (UNSUPPORTED_EXTENSIONS.contains(extension)) {
            String message = String.format("不支持的文件类型：%s。专利检索与审查系统仅支持 PDF 专利文件", fileType);
            logger.warn("文件类型验证失败: fileName={}, extension={}, fileType={}, reason=unsupported_type", 
                      fileName, extension, fileType);
            return new FileTypeValidationResult(false, message, fileType, extension);
        }

        // 对于未知的文件类型，给出提示
        String message = String.format("未知的文件类型：%s。专利检索与审查系统仅支持 PDF 专利文件", fileType);
        logger.warn("文件类型验证失败: fileName={}, extension={}, fileType={}, reason=unknown_type", 
                  fileName, extension, fileType);
        return new FileTypeValidationResult(false, message, fileType, extension);
    }

    /**
     * 提取文件扩展名
     *
     * @param fileName 文件名
     * @return 小写的文件扩展名，如果没有扩展名则返回null
     */
    private String extractFileExtension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(lastDotIndex + 1).toLowerCase();
    }

    /**
     * 根据文件扩展名获取文件类型描述
     *
     * @param extension 文件扩展名
     * @return 文件类型描述
     */
    private String getFileTypeDescription(String extension) {
        if (extension == null) {
            return "unknown";
        }

        // 根据文件扩展名返回文件类型
        switch (extension.toLowerCase()) {
            case "pdf":
                return "PDF文档";
            case "doc":
            case "docx":
                return "Word文档";
            case "xls":
            case "xlsx":
                return "Excel表格";
            case "ppt":
            case "pptx":
                return "PowerPoint演示文稿";
            case "txt":
                return "文本文件";
            case "rtf":
                return "富文本文档";
            case "md":
                return "Markdown文档";
            case "odt":
                return "OpenDocument文本";
            case "ods":
                return "OpenDocument表格";
            case "odp":
                return "OpenDocument演示文稿";
            case "html":
            case "htm":
                return "HTML文档";
            case "xml":
                return "XML文档";
            case "json":
                return "JSON文件";
            case "csv":
                return "CSV文件";
            case "epub":
                return "EPUB电子书";
            case "pages":
                return "Apple Pages文档";
            case "numbers":
                return "Apple Numbers表格";
            case "keynote":
                return "Apple Keynote演示文稿";
            case "jpg":
            case "jpeg":
                return "JPEG图片";
            case "png":
                return "PNG图片";
            case "gif":
                return "GIF图片";
            case "bmp":
                return "BMP图片";
            case "svg":
                return "SVG图片";
            case "mp4":
                return "MP4视频";
            case "avi":
                return "AVI视频";
            case "mov":
                return "MOV视频";
            case "mp3":
                return "MP3音频";
            case "wav":
                return "WAV音频";
            case "zip":
                return "ZIP压缩包";
            case "rar":
                return "RAR压缩包";
            case "7z":
                return "7Z压缩包";
            default:
                return extension.toUpperCase() + "文件";
        }
    }

    /**
     * 获取支持的文件类型列表（用于前端显示）
     *
     * @return 支持的文件类型描述列表
     */
    public Set<String> getSupportedFileTypes() {
        Set<String> supportedTypes = new HashSet<>();
        for (String extension : SUPPORTED_DOCUMENT_EXTENSIONS) {
            supportedTypes.add(getFileTypeDescription(extension));
        }
        return supportedTypes;
    }

    /**
     * 获取支持的文件扩展名列表
     *
     * @return 支持的文件扩展名集合
     */
    public Set<String> getSupportedExtensions() {
        return new HashSet<>(SUPPORTED_DOCUMENT_EXTENSIONS);
    }

    /**
     * 文件类型验证结果类
     */
    public static class FileTypeValidationResult {
        private final boolean valid;
        private final String message;
        private final String fileType;
        private final String extension;

        public FileTypeValidationResult(boolean valid, String message, String fileType, String extension) {
            this.valid = valid;
            this.message = message;
            this.fileType = fileType;
            this.extension = extension;
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public String getFileType() {
            return fileType;
        }

        public String getExtension() {
            return extension;
        }

        @Override
        public String toString() {
            return String.format("FileTypeValidationResult{valid=%s, message='%s', fileType='%s', extension='%s'}", 
                               valid, message, fileType, extension);
        }
    }
} 
