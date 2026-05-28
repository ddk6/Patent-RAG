-- Add complete raw Python patent parser result snapshot.
-- Apply once if patent_documents already exists.

ALTER TABLE patent_documents
    ADD COLUMN raw_parser_result_json JSON COMMENT '专利解析器完整原始 JSON 结果' AFTER raw_bibliographic_json;
