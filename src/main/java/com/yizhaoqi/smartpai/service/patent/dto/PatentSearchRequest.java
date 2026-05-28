package com.yizhaoqi.smartpai.service.patent.dto;

import lombok.Data;

@Data
public class PatentSearchRequest {
    private String query;
    private Integer topK = 10;
    private String fileName;
    private String publicationNo;
    private String applicationNo;
    private String title;
    private String applicant;
    private String patentType;
    private String sourceType;
    private Boolean independentClaimOnly;
}
