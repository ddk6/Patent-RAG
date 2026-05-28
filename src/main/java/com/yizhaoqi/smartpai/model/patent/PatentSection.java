package com.yizhaoqi.smartpai.model.patent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 专利说明书/摘要等结构化章节。
 */
@Data
@Entity
@Table(
        name = "patent_sections",
        indexes = {
                @Index(name = "idx_patent_sections_patent", columnList = "patent_id"),
                @Index(name = "idx_patent_sections_type", columnList = "section_type")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_patent_sections_order", columnNames = {"patent_id", "section_order"})
        }
)
public class PatentSection {

    public static final String TYPE_ABSTRACT = "ABSTRACT";
    public static final String TYPE_CLAIMS = "CLAIMS";
    public static final String TYPE_TECHNICAL_FIELD = "TECHNICAL_FIELD";
    public static final String TYPE_BACKGROUND = "BACKGROUND";
    public static final String TYPE_SUMMARY = "SUMMARY";
    public static final String TYPE_DRAWING_DESC = "DRAWING_DESC";
    public static final String TYPE_EMBODIMENT = "EMBODIMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patent_id", nullable = false)
    private Long patentId;

    @Column(name = "section_type", nullable = false, length = 50)
    private String sectionType;

    @Column(name = "section_title", length = 255)
    private String sectionTitle;

    @Column(name = "section_order", nullable = false)
    private Integer sectionOrder = 0;

    @Column(name = "text_content", columnDefinition = "LONGTEXT")
    private String textContent;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
