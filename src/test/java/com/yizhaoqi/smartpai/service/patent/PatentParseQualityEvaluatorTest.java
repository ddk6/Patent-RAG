package com.yizhaoqi.smartpai.service.patent;

import com.yizhaoqi.smartpai.config.PatentParserProperties;
import com.yizhaoqi.smartpai.service.patent.dto.PatentParserResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatentParseQualityEvaluatorTest {

    private final PatentParseQualityEvaluator evaluator = new PatentParseQualityEvaluator(new PatentParserProperties());

    @Test
    void acceptsHighQualityDirectPatentParse() {
        PatentParserResult result = new PatentParserResult();
        PatentParserResult.PatentMetadata metadata = new PatentParserResult.PatentMetadata();
        metadata.setApplicationNumber("202310587525.9");
        metadata.setPublicationNumber("CN116000000A");
        metadata.setTitle("一种数据处理方法");
        metadata.setApplicant("某某科技有限公司");
        metadata.setPatentType("发明专利申请");
        metadata.setApplicationDate("2023-01-01");
        metadata.setAbstractText("本发明涉及一种数据处理方法，可以提升处理效率。");
        result.setMetadata(metadata);

        PatentParserResult.PatentClaimItem claim1 = claim(1, true,
                "1. 一种数据处理方法，其特征在于，包括获取数据、清洗数据、生成特征并输出处理结果，其中所述处理结果用于后续业务判断。");
        PatentParserResult.PatentClaimItem claim2 = claim(2, false,
                "2. 根据权利要求1所述的数据处理方法，其特征在于，所述清洗数据包括去除重复记录和异常记录。");
        result.setClaims(List.of(claim1, claim2));

        result.setSections(List.of(section("TECHNICAL_FIELD"), section("BACKGROUND")));
        result.setChunks(List.of(
                chunk("BIBLIOGRAPHIC", "申请号: 202310587525.9\n公开号: CN116000000A\n名称: 一种数据处理方法"),
                chunk("ABSTRACT", metadata.getAbstractText()),
                chunk("CLAIM", claim1.getText()),
                chunk("CLAIM", claim2.getText()),
                chunk("DESCRIPTION", "技术领域\n本发明涉及数据处理技术领域，尤其涉及一种适用于企业系统的数据处理方法。")
        ));

        assertTrue(evaluator.evaluate(result).acceptable());
    }

    @Test
    void rejectsWeakDirectPatentParse() {
        PatentParserResult result = new PatentParserResult();
        PatentParserResult.PatentMetadata metadata = new PatentParserResult.PatentMetadata();
        metadata.setTitle("一种装置");
        result.setMetadata(metadata);
        result.setChunks(List.of(chunk("BIBLIOGRAPHIC", "名称: 一种装置")));

        assertFalse(evaluator.evaluate(result).acceptable());
    }

    private PatentParserResult.PatentClaimItem claim(int claimNo, boolean independent, String text) {
        PatentParserResult.PatentClaimItem item = new PatentParserResult.PatentClaimItem();
        item.setClaimNo(claimNo);
        item.setIndependent(independent);
        item.setText(text);
        item.setTechnicalFeaturesJson("{\"features\":[\"数据处理\"]}");
        return item;
    }

    private PatentParserResult.PatentSectionItem section(String type) {
        PatentParserResult.PatentSectionItem item = new PatentParserResult.PatentSectionItem();
        item.setSectionType(type);
        item.setTitle(type);
        item.setText("本章节包含足够的技术描述，用于判断结构化结果是否完整。");
        return item;
    }

    private PatentParserResult.PatentChunkItem chunk(String sourceType, String text) {
        PatentParserResult.PatentChunkItem item = new PatentParserResult.PatentChunkItem();
        item.setSourceType(sourceType);
        item.setText(text);
        return item;
    }
}
