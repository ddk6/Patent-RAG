-- Add parse quality diagnostics for patent structured ingestion.
-- Apply once after patent_rag_schema.sql if these columns do not exist.

ALTER TABLE patent_documents
    ADD COLUMN metadata_score DOUBLE DEFAULT NULL COMMENT '著录项解析质量分',
    ADD COLUMN claim_score DOUBLE DEFAULT NULL COMMENT '权利要求解析质量分',
    ADD COLUMN section_score DOUBLE DEFAULT NULL COMMENT '说明书章节解析质量分',
    ADD COLUMN chunk_score DOUBLE DEFAULT NULL COMMENT '检索块解析质量分',
    ADD COLUMN ocr_score DOUBLE DEFAULT NULL COMMENT '文本/OCR可用性质量分',
    ADD COLUMN overall_score DOUBLE DEFAULT NULL COMMENT '综合解析质量分',
    ADD COLUMN quality_level VARCHAR(30) DEFAULT NULL COMMENT '解析质量等级：EXCELLENT/USABLE/NEEDS_REVIEW',
    ADD COLUMN quality_issues_json JSON DEFAULT NULL COMMENT '解析质量问题列表 JSON',
    ADD INDEX idx_patent_documents_quality_level (quality_level);
