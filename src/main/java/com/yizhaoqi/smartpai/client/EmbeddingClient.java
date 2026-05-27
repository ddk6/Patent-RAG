package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.RateLimitService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 嵌入向量生成客户端
@Component
public class EmbeddingClient {

    public enum UsageType {
        UPLOAD,
        QUERY
    }

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;

    public EmbeddingClient(ObjectMapper objectMapper,
                           RateLimitService rateLimitService,
                           UsageQuotaService usageQuotaService,
                           ModelProviderConfigService modelProviderConfigService) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
    }

    @PostConstruct
    public void init() {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        logger.info("EmbeddingClient 初始化 - Provider: {}, 模型: {}, 批次大小: {}, 维度: {}, API地址: {}",
                provider.provider(), provider.model(), batchSize, provider.dimension(), provider.apiBaseUrl());
    }

    /**
     * 调用通义千问 API 生成向量
     * @param texts 输入文本列表
     * @return 对应的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        return embedWithUsage(texts, "system", UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId) {
        return embedWithUsage(texts, requesterId, UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId, UsageType usageType) {
        return embedWithUsage(texts, requesterId, usageType).vectors();
    }

    public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType) {
        try {
            String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;
            logger.info("开始生成向量，文本数量: {}", texts.size());

            List<float[]> all = new ArrayList<>(texts.size());
            int totalTokens = 0;
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
                        ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, sub)
                        : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, sub);
                logger.debug("调用向量 API, 批次: {}-{} (size={})", start, end - 1, sub.size());
                try {
                    String response = callApiOnce(sub);
                    EmbeddingApiResponse parsedResponse = parseEmbeddingResponse(response, sub);
                    usageQuotaService.settleReservation(reservation, parsedResponse.totalTokens());
                    all.addAll(parsedResponse.vectors());
                    totalTokens += parsedResponse.totalTokens();
                } catch (Exception e) {
                    usageQuotaService.abortReservation(reservation);
                    throw e;
                }
            }
            logger.info("成功生成向量，总数量: {}", all.size());
            return new EmbeddingUsageResult(all, totalTokens, currentModelVersion());
        } catch (NonRetryableEmbeddingException e) {
            logger.error("API调用失败 - 状态码: {}, 响应: {}", e.statusCode, e.responseBody);
            throw new RuntimeException(String.format(
                    "向量生成失败 - API错误: HTTP %d - %s",
                    e.statusCode,
                    e.responseBody), e);
        } catch (WebClientResponseException e) {
            // 提供详细的API响应错误信息
            logger.error("API调用失败 - 状态码: {}, 响应: {}, 请求头: {}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    e.getHeaders());
            throw new RuntimeException(String.format(
                    "向量生成失败 - API错误: HTTP %d - %s",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            logger.error("调用向量化 API 失败: {} - 类型: {}",
                    e.getMessage(),
                    e.getClass().getSimpleName(), e);
            throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
        }
    }

    private String callApiOnce(List<String> batch) {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", provider.model());
        requestBody.put("input", batch);
        if (provider.dimension() != null) {
            requestBody.put("dimension", provider.dimension());
        }
        requestBody.put("encoding_format", "float");

        int maxChars = batch.stream().mapToInt(text -> text != null ? text.length() : 0).max().orElse(0);
        int estimatedTokens = usageQuotaService.estimateEmbeddingTokens(batch);
        logger.debug("发送嵌入请求 - Provider: {}, 模型: {}, 维度: {}, 批次大小: {}, maxChars: {}, estimatedTokens: {}, 文本预览: {}",
                provider.provider(), provider.model(), provider.dimension(), batch.size(), maxChars, estimatedTokens,
                batch.isEmpty() ? "空" : batch.get(0).substring(0, Math.min(50, batch.get(0).length())) + "...");

        return buildClient(provider).post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() && status.value() != 429, response ->
                        response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new NonRetryableEmbeddingException(
                                        response.statusCode().value(), body))))
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(this::isRetryableEmbeddingError)
                        .doBeforeRetry(signal -> logger.warn("重试API调用 - 尝试: {}, 错误: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .block(Duration.ofSeconds(30));
    }

    private boolean isRetryableEmbeddingError(Throwable error) {
        if (error instanceof NonRetryableEmbeddingException) {
            return false;
        }
        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || responseException.getStatusCode().is5xxServerError();
        }
        return true;
    }

    private WebClient buildClient(ModelProviderConfigService.ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(provider.apiBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // WebClient 的默认缓冲区大小限制（256KB）, 这里调高到 16MB
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    }

    private EmbeddingApiResponse parseEmbeddingResponse(String response, List<String> inputTexts) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");  // 兼容模式下使用data字段
        if (data == null || !data.isArray()) {
            throw new RuntimeException("API 响应格式错误: data 字段不存在或不是数组");
        }
        
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }

        JsonNode usage = jsonNode.path("usage");
        int totalTokens = usage.path("total_tokens").asInt(usage.path("input_tokens").asInt(0));
        return new EmbeddingApiResponse(vectors, totalTokens > 0 ? totalTokens : usageQuotaService.estimateEmbeddingTokens(inputTexts));
    }

    public String currentModelVersion() {
        ModelProviderConfigService.ActiveProviderView provider = modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        return provider.provider() + ":" + provider.model() + ":" + provider.dimension();
    }

    private record EmbeddingApiResponse(List<float[]> vectors, int totalTokens) {
    }

    public record EmbeddingUsageResult(List<float[]> vectors, int totalTokens, String modelVersion) {
    }

    private static class NonRetryableEmbeddingException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        private NonRetryableEmbeddingException(int statusCode, String responseBody) {
            super("Non-retryable embedding API error: HTTP " + statusCode + " - " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}
