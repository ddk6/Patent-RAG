package com.yizhaoqi.smartpai.service.patent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PatentParserResult {
    private String parserVersion;
    private PatentMetadata metadata;
    private List<PatentClaimItem> claims = new ArrayList<>();
    private List<PatentSectionItem> sections = new ArrayList<>();
    private List<PatentChunkItem> chunks = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    @Data
    public static class PatentMetadata {
        private String applicationNumber;
        private String publicationNumber;
        private String title;
        private String applicant;
        private String inventors;
        private String ipc;
        private String patentType;
        private String applicationDate;
        private String publicationDate;
        private String agency;
        private String agent;
        private String address;
        private String abstractText;
        private String mainClaimText;
        private String rawBibliographicJson;
    }

    @Data
    public static class PatentClaimItem {
        private Integer claimNo;
        private String text;
        private boolean independent;
        private Integer dependsOnClaimNo;
        private String technicalFeaturesJson;
        private Integer pageNumber;
        private String anchorText;
    }

    @Data
    public static class PatentSectionItem {
        private String sectionType;
        private String title;
        private Integer order;
        private String text;
        private Integer pageStart;
        private Integer pageEnd;
        private String anchorText;
    }

    @Data
    public static class PatentChunkItem {
        private String sourceType;
        private Long sourceId;
        private Integer chunkNo;
        private String text;
        private Integer pageNumber;
        private String anchorText;
        private String sectionPath;
        private Integer claimNo;
        private boolean independentClaim;
        private Integer tokenCount;
    }
}
