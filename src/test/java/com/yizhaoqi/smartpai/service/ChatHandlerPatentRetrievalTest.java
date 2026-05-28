package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.patent.PatentSearchService;
import com.yizhaoqi.smartpai.service.patent.dto.PatentSearchRequest;
import com.yizhaoqi.smartpai.service.patent.dto.PatentSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class ChatHandlerPatentRetrievalTest {

    private PatentSearchService patentSearchService;
    private ChatHandler chatHandler;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);
        patentSearchService = Mockito.mock(PatentSearchService.class);
        LlmProviderRouter llmProviderRouter = Mockito.mock(LlmProviderRouter.class);
        RateLimitService rateLimitService = Mockito.mock(RateLimitService.class);
        UsageQuotaService usageQuotaService = Mockito.mock(UsageQuotaService.class);
        ConversationMemoryService conversationMemoryService = Mockito.mock(ConversationMemoryService.class);
        ThreadPoolTaskExecutor executor = Mockito.mock(ThreadPoolTaskExecutor.class);

        chatHandler = new ChatHandler(
                redisTemplate,
                patentSearchService,
                llmProviderRouter,
                rateLimitService,
                usageQuotaService,
                conversationMemoryService,
                executor
        );
    }

    @Test
    void shouldRestrictClaimQuestionsToClaimEvidence() {
        when(patentSearchService.search(Mockito.any(PatentSearchRequest.class), eq("1")))
                .thenReturn(List.of(samplePatentResult()));

        @SuppressWarnings("unchecked")
        List<SearchResult> results = (List<SearchResult>) ReflectionTestUtils.invokeMethod(
                chatHandler,
                "retrieveRagContext",
                "请找出 2023801024975.pdf 中权利要求1的每一个限定词，并说明这些限定对保护范围的影响。",
                "1"
        );

        ArgumentCaptor<PatentSearchRequest> requestCaptor = ArgumentCaptor.forClass(PatentSearchRequest.class);
        Mockito.verify(patentSearchService).search(requestCaptor.capture(), eq("1"));

        PatentSearchRequest request = requestCaptor.getValue();
        assertNotNull(request);
        assertEquals(20, request.getTopK());
        assertEquals("CLAIM", request.getSourceType());
        assertFalse(Boolean.TRUE.equals(request.getIndependentClaimOnly()));
        assertEquals(1, results.size());
        assertTrue(results.get(0).getTextContent().contains("权利要求1"));
    }

    @Test
    void shouldRequestIndependentClaimsForIndependentClaimQuestions() {
        when(patentSearchService.search(Mockito.any(PatentSearchRequest.class), eq("1")))
                .thenReturn(List.of(samplePatentResult()));

        ReflectionTestUtils.invokeMethod(
                chatHandler,
                "retrieveRagContext",
                "请提取 2023801024975.pdf 的独立权利要求并分析保护范围。",
                "1"
        );

        ArgumentCaptor<PatentSearchRequest> requestCaptor = ArgumentCaptor.forClass(PatentSearchRequest.class);
        Mockito.verify(patentSearchService).search(requestCaptor.capture(), eq("1"));

        PatentSearchRequest request = requestCaptor.getValue();
        assertNotNull(request);
        assertEquals("CLAIM", request.getSourceType());
        assertTrue(Boolean.TRUE.equals(request.getIndependentClaimOnly()));
    }

    private PatentSearchResult samplePatentResult() {
        PatentSearchResult result = new PatentSearchResult();
        result.setPatentId(1L);
        result.setPatentChunkId(11L);
        result.setFileMd5("md5");
        result.setFileName("2023801024975.pdf");
        result.setChunkNo(1);
        result.setTextContent("1. 一种装置，其特征在于，包括模块A和模块B。");
        result.setSourceType("CLAIM");
        result.setClaimNo(1);
        result.setIndependentClaim(true);
        result.setRetrievalMode("HYBRID");
        return result;
    }
}
