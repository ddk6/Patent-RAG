package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.config.PatentParserProperties;
import com.yizhaoqi.smartpai.service.patent.dto.PatentParserRequest;
import com.yizhaoqi.smartpai.service.patent.dto.PatentParserResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

@Component
public class PatentParserClient {

    private static final Logger logger = LoggerFactory.getLogger(PatentParserClient.class);

    private final PatentParserProperties properties;
    private final WebClient webClient;

    public PatentParserClient(PatentParserProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    public PatentParserResult parse(PatentParserRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Patent parser service is disabled");
        }

        try {
            PatentParserResult result = webClient.post()
                    .uri("/parse-patent")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(PatentParserResult.class)
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .block();

            if (result == null) {
                throw new IllegalStateException("Patent parser returned empty response");
            }

            logger.info("Patent parser completed: fileMd5={}, claims={}, sections={}, chunks={}",
                    request.fileMd5(),
                    result.getClaims() != null ? result.getClaims().size() : 0,
                    result.getSections() != null ? result.getSections().size() : 0,
                    result.getChunks() != null ? result.getChunks().size() : 0);
            return result;
        } catch (WebClientResponseException e) {
            logger.error("Patent parser returned HTTP error: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (Exception e) {
            logger.error("Patent parser request failed: fileMd5={}, error={}", request.fileMd5(), e.getMessage(), e);
            throw new RuntimeException("Patent parser request failed: " + e.getMessage(), e);
        }
    }
}
