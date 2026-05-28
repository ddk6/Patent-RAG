package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.service.patent.PatentReprocessService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/patents")
public class PatentProcessingController {

    private final PatentReprocessService patentReprocessService;

    public PatentProcessingController(PatentReprocessService patentReprocessService) {
        this.patentReprocessService = patentReprocessService;
    }

    @PostMapping("/{fileMd5}/reprocess")
    public ResponseEntity<Map<String, Object>> reprocessPatent(
            @PathVariable String fileMd5,
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            @RequestAttribute("userId") String userId,
            @RequestAttribute(value = "role", required = false) String role) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PATENT_REPROCESS");
        try {
            LogUtils.logBusiness("PATENT_REPROCESS", userId,
                    "接收到专利链路重试/补偿请求: fileMd5=%s, force=%s, role=%s",
                    fileMd5, force, role);

            PatentReprocessService.PatentReprocessResult result =
                    patentReprocessService.enqueuePatentReprocess(fileMd5, userId, role, force);

            Map<String, Object> response = new HashMap<>(4);
            response.put("code", 200);
            response.put("message", "专利链路重试任务已提交");
            response.put("data", result);
            monitor.end("专利链路重试任务提交成功");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> response = new HashMap<>(4);
            response.put("code", e.getStatus().value());
            response.put("message", e.getMessage());
            monitor.end("专利链路重试任务提交失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>(4);
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "专利链路重试任务提交失败: " + e.getMessage());
            monitor.end("专利链路重试任务提交失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
