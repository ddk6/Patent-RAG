-- Switch upload records to patent-first mode.
-- Existing GENERAL rows are kept for history; new uploads default to PATENT.

ALTER TABLE file_upload
    MODIFY COLUMN document_type VARCHAR(32) NOT NULL DEFAULT 'PATENT' COMMENT '文档类型：PATENT专利文档 GENERAL历史通用文档';
