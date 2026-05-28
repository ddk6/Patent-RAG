package com.yizhaoqi.smartpai.model.patent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 专利权利要求。
 */
@Data
@Entity
@Table(
        name = "patent_claims",
        indexes = {
                @Index(name = "idx_patent_claims_patent", columnList = "patent_id"),
                @Index(name = "idx_patent_claims_independent", columnList = "is_independent"),
                @Index(name = "idx_patent_claims_depends_on", columnList = "depends_on_claim_no")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_patent_claims_no", columnNames = {"patent_id", "claim_no"})
        }
)
public class PatentClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patent_id", nullable = false)
    private Long patentId;

    @Column(name = "claim_no", nullable = false)
    private Integer claimNo;

    @Column(name = "text_content", nullable = false, columnDefinition = "LONGTEXT")
    private String textContent;

    @Column(name = "is_independent", nullable = false)
    private boolean isIndependent = false;

    @Column(name = "depends_on_claim_no")
    private Integer dependsOnClaimNo;

    @Column(name = "technical_features_json", columnDefinition = "JSON")
    private String technicalFeaturesJson;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
