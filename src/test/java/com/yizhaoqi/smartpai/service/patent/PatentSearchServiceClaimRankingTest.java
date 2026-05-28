package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.entity.PatentEsDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatentSearchServiceClaimRankingTest {

    private PatentSearchService service;

    @BeforeEach
    void setUp() {
        service = new PatentSearchService(
                Mockito.mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                Mockito.mock(com.yizhaoqi.smartpai.client.EmbeddingClient.class),
                Mockito.mock(com.yizhaoqi.smartpai.repository.UserRepository.class),
                Mockito.mock(com.yizhaoqi.smartpai.service.OrgTagCacheService.class)
        );
    }

    @Test
    void shouldPreferIndependentClaimEvidenceOverDescriptionWhenClaimFocused() {
        PatentEsDocument description = new PatentEsDocument();
        description.setSourceType("DESCRIPTION");
        description.setTextContent("说明书中提到权利要求1和实施方式。");

        PatentEsDocument claim = new PatentEsDocument();
        claim.setSourceType("CLAIM");
        claim.setClaimNo(1);
        claim.setIndependentClaim(true);
        claim.setTextContent("1. 一种装置，其特征在于，包括模块A和模块B。");

        double descriptionBoost = (double) ReflectionTestUtils.invokeMethod(
                service, "claimEvidenceBoost", description, true);
        double claimBoost = (double) ReflectionTestUtils.invokeMethod(
                service, "claimEvidenceBoost", claim, true);

        assertTrue(claimBoost > descriptionBoost);
    }

    @Test
    void shouldRecognizeOnlyRealClaimEvidenceAsClaimLike() {
        PatentEsDocument description = new PatentEsDocument();
        description.setSourceType("DESCRIPTION");
        description.setTextContent("背景技术部分描述现有技术问题，并未给出完整权利要求正文。");

        PatentEsDocument claim = new PatentEsDocument();
        claim.setSourceType("CLAIM");
        claim.setClaimNo(1);
        claim.setTextContent("1. 一种数据处理方法，其特征在于，包括获取数据和输出结果。");

        boolean descriptionLooksLikeClaim = (boolean) ReflectionTestUtils.invokeMethod(
                service, "looksLikeClaimEvidence", description);
        boolean claimLooksLikeClaim = (boolean) ReflectionTestUtils.invokeMethod(
                service, "looksLikeClaimEvidence", claim);

        assertFalse(descriptionLooksLikeClaim);
        assertTrue(claimLooksLikeClaim);
    }
}
