package com.yizhaoqi.smartpai.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Elasticsearch 中的专利检索块。
 */
@Data
public class PatentEsDocument {

    private String id;
    private Long patentId;
    private Long patentChunkId;
    private String fileMd5;
    private String fileName;
    private Integer chunkNo;
    private String textContent;
    private String sourceType;
    private Long sourceId;
    private Integer claimNo;
    private boolean independentClaim;
    private String sectionPath;
    private Integer pageNumber;
    private String anchorText;
    private float[] vector;
    private String modelVersion;
    private String userId;
    private String orgTag;
    @JsonProperty("isPublic")
    private boolean isPublic;
    private String publicationNo;
    private String applicationNo;
    private String title;
    private String applicant;
    private String patentType;
    private String publicationDate;
    private String applicationDate;

    public PatentEsDocument() {
    }

    public PatentEsDocument(String id,
                            Long patentId,
                            Long patentChunkId,
                            String fileMd5,
                            String fileName,
                            Integer chunkNo,
                            String textContent,
                            String sourceType,
                            Long sourceId,
                            Integer claimNo,
                            boolean independentClaim,

                            String sectionPath,
                            Integer pageNumber,
                            String anchorText,
                            float[] vector,
                            String modelVersion,
                            String userId,
                            String orgTag,
                            boolean isPublic,
                            String publicationNo,
                            String applicationNo,
                            String title,
                            String applicant,
                            String patentType,
                            String publicationDate,
                            String applicationDate) {
        this.id = id;
        this.patentId = patentId;
        this.patentChunkId = patentChunkId;
        this.fileMd5 = fileMd5;
        this.fileName = fileName;
        this.chunkNo = chunkNo;
        this.textContent = textContent;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.claimNo = claimNo;
        this.independentClaim = independentClaim;
        this.sectionPath = sectionPath;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.publicationNo = publicationNo;
        this.applicationNo = applicationNo;
        this.title = title;
        this.applicant = applicant;
        this.patentType = patentType;
        this.publicationDate = publicationDate;
        this.applicationDate = applicationDate;
    }
}
