package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.patent.PatentSearchService;
import com.yizhaoqi.smartpai.service.patent.dto.PatentSearchRequest;
import com.yizhaoqi.smartpai.service.patent.dto.PatentSearchResult;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/search/patent")
public class PatentSearchController {

    private final PatentSearchService patentSearchService;

    public PatentSearchController(PatentSearchService patentSearchService) {
        this.patentSearchService = patentSearchService;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam(required = false) String query,
                                      @RequestParam(defaultValue = "10") int topK,
                                      @RequestParam(required = false) String publicationNo,
                                      @RequestParam(required = false) String applicationNo,
                                      @RequestParam(required = false) String title,
                                      @RequestParam(required = false) String applicant,
                                      @RequestParam(required = false) String patentType,
                                      @RequestParam(required = false) String sourceType,
                                      @RequestParam(required = false) Boolean independentClaimOnly,
                                      @RequestAttribute(value = "userId", required = false) String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PATENT_SEARCH");
        try {
            PatentSearchRequest request = new PatentSearchRequest();
            request.setQuery(query);
            request.setTopK(topK);
            request.setPublicationNo(publicationNo);
            request.setApplicationNo(applicationNo);
            request.setTitle(title);
            request.setApplicant(applicant);
            request.setPatentType(patentType);
            request.setSourceType(sourceType);
            request.setIndependentClaimOnly(independentClaimOnly);

            List<PatentSearchResult> results = patentSearchService.search(request, userId);
            monitor.end("专利检索成功");

            Map<String, Object> body = new HashMap<>(4);
            body.put("code", 200);
            body.put("message", "success");
            body.put("data", results);
            return body;
        } catch (Exception e) {
            monitor.end("专利检索失败: " + e.getMessage());
            Map<String, Object> body = new HashMap<>(4);
            body.put("code", 500);
            body.put("message", e.getMessage());
            body.put("data", Collections.emptyList());
            return body;
        }
    }
}
