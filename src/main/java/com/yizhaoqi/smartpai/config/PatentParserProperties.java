package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "patent.parser")
public class PatentParserProperties {

    /**
     * Whether to call the external Python patent parser.
     */
    private boolean enabled = true;

    /**
     * Python patent parser service base URL.
     */
    private String baseUrl = "http://localhost:8091";

    /**
     * Parser request timeout.
     */
    private int timeoutMs = 60000;

    /**
     * Try the fast direct-PDF patent parser before MinerU for documents already marked as patents.
     */
    private boolean directEnabled = true;

    /**
     * Minimum quality score required for accepting direct-PDF parsing and skipping MinerU.
     */
    private double directQualityThreshold = 0.70;

    private int directMinMetadataSignals = 2;

    private int directMinClaims = 1;

    private int directMinChunks = 3;

    private int directMinChunkTextChars = 300;
}
