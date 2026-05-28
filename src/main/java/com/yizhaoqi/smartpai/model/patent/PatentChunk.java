package com.yizhaoqi.smartpai.model.patent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 专利专用检索块。
 */
@Data
@Entity
@Table(
        name = "patent_chunks",
        indexes = {
                @Index(name = "idx_patent_chunks_patent", columnList = "patent_id"),
                @Index(name = "idx_patent_chunks_source", columnList = "source_type,source_id"),
                @Index(name = "idx_patent_chunks_claim_no", columnList = "claim_no"),
                @Index(name = "idx_patent_chunks_independent_claim", columnList = "is_independent_claim")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_patent_chunks_no", columnNames = {"patent_id", "chunk_no"})
        }
)
public class PatentChunk {

    public static final String SOURCE_BIBLIOGRAPHIC = "BIBLIOGRAPHIC";
    public static final String SOURCE_ABSTRACT = "ABSTRACT";
    public static final String SOURCE_CLAIM = "CLAIM";
    public static final String SOURCE_DESCRIPTION = "DESCRIPTION";
    public static final String SOURCE_DRAWING = "DRAWING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patent_id", nullable = false)
    private Long patentId;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "chunk_no", nullable = false)
    private Integer chunkNo;

    @Column(name = "text_content", nullable = false, columnDefinition = "LONGTEXT")
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Column(name = "section_path", length = 500)
    private String sectionPath;

    @Column(name = "claim_no")
    private Integer claimNo;

    @Column(name = "is_independent_claim", nullable = false)
    private boolean isIndependentClaim = false;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "model_version", length = 32)
    private String modelVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
