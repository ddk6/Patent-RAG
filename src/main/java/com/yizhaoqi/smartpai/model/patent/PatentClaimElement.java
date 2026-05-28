package com.yizhaoqi.smartpai.model.patent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 权利要求技术特征/构成要素。
 * 用于后续特征级检索、对比文件覆盖率计算和审查证据映射。
 */
@Data
@Entity
@Table(
        name = "patent_claim_elements",
        indexes = {
                @Index(name = "idx_claim_elements_patent", columnList = "patent_id"),
                @Index(name = "idx_claim_elements_claim", columnList = "claim_id"),
                @Index(name = "idx_claim_elements_claim_no", columnList = "patent_id,claim_no"),
                @Index(name = "idx_claim_elements_type", columnList = "element_type"),
                @Index(name = "idx_claim_elements_review_status", columnList = "review_status")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_claim_element_order", columnNames = {"claim_id", "element_no"})
        }
)
public class PatentClaimElement {

    public static final String TYPE_PREAMBLE = "PREAMBLE";
    public static final String TYPE_COMPONENT = "COMPONENT";
    public static final String TYPE_STEP = "STEP";
    public static final String TYPE_CONDITION = "CONDITION";
    public static final String TYPE_EFFECT = "EFFECT";
    public static final String TYPE_PARAMETER = "PARAMETER";
    public static final String TYPE_LIMITATION = "LIMITATION";

    public static final String METHOD_RULE = "RULE";
    public static final String METHOD_LLM = "LLM";
    public static final String METHOD_HYBRID = "HYBRID";
    public static final String METHOD_MANUAL = "MANUAL";

    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_CONFIRMED = "CONFIRMED";
    public static final String REVIEW_REJECTED = "REJECTED";
    public static final String REVIEW_EDITED = "EDITED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patent_id", nullable = false)
    private Long patentId;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "claim_no", nullable = false)
    private Integer claimNo;

    @Column(name = "element_no", nullable = false)
    private Integer elementNo;

    @Column(name = "element_label", length = 50)
    private String elementLabel;

    @Column(name = "element_type", nullable = false, length = 50)
    private String elementType = TYPE_COMPONENT;

    @Column(name = "text_content", nullable = false, columnDefinition = "LONGTEXT")
    private String textContent;

    @Column(name = "normalized_text", columnDefinition = "LONGTEXT")
    private String normalizedText;

    @Column(name = "parent_element_id")
    private Long parentElementId;

    @Column(name = "depends_on_element_id")
    private Long dependsOnElementId;

    @Column(name = "is_core", nullable = false)
    private boolean isCore = false;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "extraction_method", nullable = false, length = 50)
    private String extractionMethod = METHOD_RULE;

    @Column(name = "review_status", nullable = false, length = 30)
    private String reviewStatus = REVIEW_PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
