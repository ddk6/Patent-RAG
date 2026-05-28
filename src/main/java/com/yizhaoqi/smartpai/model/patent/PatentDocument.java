package com.yizhaoqi.smartpai.model.patent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 专利结构化文档。
 * 保存首页著录项、摘要和专利链路的权限元数据。
 */
@Data
@Entity
@Table(
        name = "patent_documents",
        indexes = {
                @Index(name = "idx_patent_documents_file_md5", columnList = "file_md5"),
                @Index(name = "idx_patent_documents_user", columnList = "user_id"),
                @Index(name = "idx_patent_documents_org_tag", columnList = "org_tag"),
                @Index(name = "idx_patent_documents_publication_no", columnList = "publication_no"),
                @Index(name = "idx_patent_documents_application_no", columnList = "application_no"),
                @Index(name = "idx_patent_documents_title", columnList = "title")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_patent_documents_upload", columnNames = "upload_id")
        }
)
public class PatentDocument {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String QUALITY_EXCELLENT = "EXCELLENT";
    public static final String QUALITY_USABLE = "USABLE";
    public static final String QUALITY_NEEDS_REVIEW = "NEEDS_REVIEW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false)
    private Long uploadId;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "patent_type", length = 50)
    private String patentType;

    @Column(name = "publication_no", length = 64)
    private String publicationNo;

    @Column(name = "application_no", length = 64)
    private String applicationNo;

    @Column(length = 512)
    private String title;

    @Column(length = 1024)
    private String applicant;

    @Column(length = 1024)
    private String inventor;

    @Column(name = "application_date")
    private LocalDate applicationDate;

    @Column(name = "publication_date")
    private LocalDate publicationDate;

    @Column(name = "ipc_classification", length = 512)
    private String ipcClassification;

    @Column(length = 512)
    private String agency;

    @Column(length = 512)
    private String agent;

    @Column(length = 1024)
    private String address;

    @Column(name = "abstract_text", columnDefinition = "LONGTEXT")
    private String abstractText;

    @Column(name = "main_claim_text", columnDefinition = "LONGTEXT")
    private String mainClaimText;

    @Column(name = "raw_bibliographic_json", columnDefinition = "JSON")
    private String rawBibliographicJson;

    @Column(name = "raw_parser_result_json", columnDefinition = "JSON")
    private String rawParserResultJson;

    @Column(name = "parser_version", length = 32)
    private String parserVersion;

    @Column(name = "parse_status", nullable = false, length = 20)
    private String parseStatus = STATUS_PENDING;

    @Column(name = "parse_error", columnDefinition = "TEXT")
    private String parseError;

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @Column(name = "metadata_score")
    private Double metadataScore;

    @Column(name = "claim_score")
    private Double claimScore;

    @Column(name = "section_score")
    private Double sectionScore;

    @Column(name = "chunk_score")
    private Double chunkScore;

    @Column(name = "ocr_score")
    private Double ocrScore;

    @Column(name = "overall_score")
    private Double overallScore;

    @Column(name = "quality_level", length = 30)
    private String qualityLevel;

    @Column(name = "quality_issues_json", columnDefinition = "JSON")
    private String qualityIssuesJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
