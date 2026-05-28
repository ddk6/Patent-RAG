package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.service.MinerUService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatentDocumentDetectorTest {

    private final PatentDocumentDetector detector = new PatentDocumentDetector();

    @Test
    void detectsChinesePatentWithStrongSignals() {
        MinerUService.MinerUParseResult result = new MinerUService.MinerUParseResult();
        result.setFullMd("""
                中华人民共和国国家知识产权局
                发明专利申请
                (21) 申请号 202310587525.9
                (43) 申请公布日 2023.11.17
                (71) 申请人 某某科技有限公司

                权利要求书
                1. 一种数据处理方法，其特征在于，包括如下步骤。

                说明书
                技术领域
                本发明涉及数据处理技术领域。
                """);

        assertTrue(detector.isPatent(result, "2023105875259.pdf"));
    }

    @Test
    void doesNotDetectGeneralDocumentWithWeakSignals() {
        MinerUService.MinerUParseResult result = new MinerUService.MinerUParseResult();
        result.setFullMd("""
                项目周报
                本周完成了通用知识库上传、文档解析和检索体验优化。
                后续计划继续改进 RAG 召回效果。
                """);

        assertFalse(detector.isPatent(result, "weekly-report.pdf"));
    }
}
