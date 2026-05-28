-- Add claim-level technical feature elements for patent review assistance.
-- Apply once after patent_rag_schema.sql if patent_claim_elements does not exist.

CREATE TABLE IF NOT EXISTS patent_claim_elements (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    patent_id BIGINT NOT NULL COMMENT '关联 patent_documents.id',
    claim_id BIGINT NOT NULL COMMENT '关联 patent_claims.id',
    claim_no INT NOT NULL COMMENT '权利要求编号',
    element_no INT NOT NULL COMMENT '技术特征序号',
    element_label VARCHAR(50) DEFAULT NULL COMMENT '特征标签，如 F1/F2/S1',
    element_type VARCHAR(50) NOT NULL DEFAULT 'COMPONENT' COMMENT '特征类型：PREAMBLE/COMPONENT/STEP/CONDITION/EFFECT/PARAMETER/LIMITATION',
    text_content LONGTEXT NOT NULL COMMENT '技术特征原文',
    normalized_text LONGTEXT DEFAULT NULL COMMENT '规范化技术特征文本',
    parent_element_id BIGINT DEFAULT NULL COMMENT '父级技术特征 id',
    depends_on_element_id BIGINT DEFAULT NULL COMMENT '依赖技术特征 id',
    is_core TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否核心特征',
    confidence DECIMAL(5,4) DEFAULT NULL COMMENT '抽取置信度',
    extraction_method VARCHAR(50) NOT NULL DEFAULT 'RULE' COMMENT '抽取方式：RULE/LLM/HYBRID/MANUAL',
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '人工复核状态：PENDING/CONFIRMED/REJECTED/EDITED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_claim_element_order (claim_id, element_no),
    INDEX idx_claim_elements_patent (patent_id),
    INDEX idx_claim_elements_claim (claim_id),
    INDEX idx_claim_elements_claim_no (patent_id, claim_no),
    INDEX idx_claim_elements_type (element_type),
    INDEX idx_claim_elements_review_status (review_status),
    CONSTRAINT fk_claim_elements_patent FOREIGN KEY (patent_id) REFERENCES patent_documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_elements_claim FOREIGN KEY (claim_id) REFERENCES patent_claims(id) ON DELETE CASCADE
) COMMENT='权利要求技术特征/构成要素表';
