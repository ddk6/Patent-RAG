package com.yizhaoqi.smartpai.service.patent.dto;

import lombok.Data;

@Data
public class PatentSearchResult {
    private Long patentId;
    private Long patentChunkId;
    private String fileMd5;
    private String fileName;
    private Integer chunkNo;
    private String textContent;
    private Double score;
    private String sourceType;
    private Integer claimNo;
    private boolean independentClaim;
    private String sectionPath;
    private Integer pageNumber;
    private String anchorText;
    private String publicationNo;
    private String applicationNo;
    private String title;
    private String applicant;
    private String patentType;
    private String retrievalMode;

    public PatentSearchResult() {
    }

    public PatentSearchResult(Long patentId,
                              Long patentChunkId,
                              String fileMd5,
                              String fileName,
                              Integer chunkNo,
                              String textContent,
                              Double score,
                              String sourceType,
                              Integer claimNo,
                              boolean independentClaim,
                              String sectionPath,
                              Integer pageNumber,
                              String anchorText,
                              String publicationNo,
                              String applicationNo,
                              String title,
                              String applicant,
                              String patentType,
                              String retrievalMode) {
        this.patentId = patentId;
        this.patentChunkId = patentChunkId;
        this.fileMd5 = fileMd5;
        this.fileName = fileName;
        this.chunkNo = chunkNo;
        this.textContent = textContent;
        this.score = score;
        this.sourceType = sourceType;
        this.claimNo = claimNo;
        this.independentClaim = independentClaim;
        this.sectionPath = sectionPath;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.publicationNo = publicationNo;
        this.applicationNo = applicationNo;
        this.title = title;
        this.applicant = applicant;
        this.patentType = patentType;
        this.retrievalMode = retrievalMode;
    }
}
