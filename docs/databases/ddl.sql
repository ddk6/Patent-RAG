CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户唯一标识',
                       username VARCHAR(255) NOT NULL UNIQUE COMMENT '用户名，唯一',
                       password VARCHAR(255) NOT NULL COMMENT '加密后的密码',
                       role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '用户角色',
                       org_tags VARCHAR(255) DEFAULT NULL COMMENT '用户所属组织标签，多个用逗号分隔',
                       primary_org VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户主组织标签',
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                       INDEX idx_username (username) COMMENT '用户名索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
CREATE TABLE organization_tags (
                                   tag_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '标签唯一标识',
                                   name VARCHAR(100) NOT NULL COMMENT '标签名称',
                                   description TEXT COMMENT '描述',
                                   parent_tag VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '父标签ID',
                                   upload_max_size_bytes BIGINT DEFAULT NULL COMMENT '非管理员上传文件大小上限，单位字节',
                                   created_by BIGINT NOT NULL COMMENT '创建者ID',
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                   FOREIGN KEY (parent_tag) REFERENCES organization_tags(tag_id) ON DELETE SET NULL,
                                   FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织标签表';


CREATE TABLE file_upload (
                             id           BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键',
                             file_md5     VARCHAR(32)      NOT NULL COMMENT '文件 MD5',
                             file_name    VARCHAR(255)     NOT NULL COMMENT '文件名称',
                             total_size   BIGINT           NOT NULL COMMENT '文件大小',
                             status       TINYINT          NOT NULL DEFAULT 0 COMMENT '上传状态：0上传中 1已完成 2合并中',
                             user_id      VARCHAR(64)      NOT NULL COMMENT '用户 ID',
                             org_tag      VARCHAR(50)      DEFAULT NULL COMMENT '组织标签',
                             is_public    BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '是否公开',
                             estimated_embedding_tokens BIGINT DEFAULT NULL COMMENT '预估 embedding token 数',
                             estimated_chunk_count INT DEFAULT NULL COMMENT '预估切片数',
                             actual_embedding_tokens BIGINT DEFAULT NULL COMMENT '实际 embedding token 数',
                             actual_chunk_count INT DEFAULT NULL COMMENT '实际切片数',
                             document_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL' COMMENT '文档类型：GENERAL通用文档 PATENT专利文档',
                             parse_method VARCHAR(20) DEFAULT NULL COMMENT '解析方法：TIKA/MINERU/PATENT/PATENT_DIRECT',
                             parse_status VARCHAR(20) DEFAULT NULL COMMENT '解析状态：PENDING/PROCESSING/COMPLETED/FAILED',
                             parsed_at TIMESTAMP NULL DEFAULT NULL COMMENT '解析完成时间',
                             created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             merged_at    TIMESTAMP        NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '合并时间',
                             PRIMARY KEY (id),
                             UNIQUE KEY uk_md5_user (file_md5, user_id),
                             INDEX idx_user (user_id),
                             INDEX idx_org_tag (org_tag),
                             INDEX idx_document_type (document_type),
                             INDEX idx_parse_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传记录';
CREATE TABLE chunk_info (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分块记录唯一标识',
                            file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                            chunk_index INT NOT NULL COMMENT '分块序号',
                            chunk_md5 VARCHAR(32) NOT NULL COMMENT '分块的MD5值',
                            storage_path VARCHAR(255) NOT NULL COMMENT '分块在存储系统中的路径',
                            UNIQUE KEY uk_file_md5_chunk_index (file_md5, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分块信息表';

CREATE TABLE document_vectors (
                                  vector_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '向量记录唯一标识',
                                  file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                                  chunk_id INT NOT NULL COMMENT '文本分块序号',
                                  text_content TEXT COMMENT '文本内容',
                                  page_number INT COMMENT 'PDF页码，用于引用定位',
                                  anchor_text VARCHAR(255) COMMENT '页内定位锚点文本',
                                  model_version VARCHAR(32) COMMENT '向量模型版本',
                                  user_id VARCHAR(64) NOT NULL COMMENT '上传用户ID',
                                  org_tag VARCHAR(50) COMMENT '文件所属组织标签',
                                  is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '文件是否公开',
                                  section_path VARCHAR(500) COMMENT '层级路径',
                                  chunk_type VARCHAR(50) COMMENT '分块类型',
                                  is_key_clause BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否关键条款',
                                  token_count INT COMMENT 'token 数',
                                  UNIQUE KEY uk_document_vector_file_user_chunk (file_md5, user_id, chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档向量存储表';

CREATE TABLE mineru_parse_result (
                                     id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
                                     file_md5 VARCHAR(32) NOT NULL COMMENT '文件 MD5',
                                     full_md LONGTEXT COMMENT '完整 Markdown 内容',
                                     content_json LONGTEXT COMMENT 'content_list_v2.json 内容',
                                     layout_json LONGTEXT COMMENT 'layout.json 内容',
                                     mineru_batch_id VARCHAR(64) COMMENT 'MinerU batch_id',
                                     parse_status VARCHAR(20) COMMENT '解析状态',
                                     parse_error TEXT COMMENT '错误信息',
                                     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                     UNIQUE KEY uk_mineru_parse_result_file_md5 (file_md5),
                                     INDEX idx_file_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MinerU 解析结果表';

CREATE TABLE patent_documents (
                                  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '专利结构化文档主键',
                                  upload_id BIGINT NOT NULL COMMENT '关联 file_upload.id',
                                  file_md5 VARCHAR(32) NOT NULL COMMENT '文件 MD5',
                                  file_name VARCHAR(255) NOT NULL COMMENT '文件名称',
                                  user_id VARCHAR(64) NOT NULL COMMENT '上传用户 ID',
                                  org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
                                  is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
                                  patent_type VARCHAR(50) DEFAULT NULL COMMENT '专利类型：发明/实用新型/外观设计等',
                                  publication_no VARCHAR(64) DEFAULT NULL COMMENT '公开号/公告号',
                                  application_no VARCHAR(64) DEFAULT NULL COMMENT '申请号',
                                  title VARCHAR(512) DEFAULT NULL COMMENT '专利名称',
                                  applicant VARCHAR(1024) DEFAULT NULL COMMENT '申请人',
                                  inventor VARCHAR(1024) DEFAULT NULL COMMENT '发明人/设计人',
                                  application_date DATE DEFAULT NULL COMMENT '申请日',
                                  publication_date DATE DEFAULT NULL COMMENT '公开/公告日',
                                  ipc_classification VARCHAR(512) DEFAULT NULL COMMENT 'IPC 分类号',
                                  agency VARCHAR(512) DEFAULT NULL COMMENT '专利代理机构',
                                  agent VARCHAR(512) DEFAULT NULL COMMENT '代理人',
                                  address VARCHAR(1024) DEFAULT NULL COMMENT '申请人地址',
                                  abstract_text LONGTEXT COMMENT '摘要',
                                  main_claim_text LONGTEXT COMMENT '主权利要求文本',
                                  raw_bibliographic_json JSON COMMENT '首页著录项原始结构化 JSON',
                                  raw_parser_result_json JSON COMMENT '专利解析器完整原始 JSON 结果',
                                  parser_version VARCHAR(32) DEFAULT NULL COMMENT '专利解析器版本',
                                  parse_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING/PROCESSING/COMPLETED/FAILED',
                                  parse_error TEXT COMMENT '解析错误信息',
                                  parsed_at TIMESTAMP NULL DEFAULT NULL COMMENT '解析完成时间',
                                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  UNIQUE KEY uk_patent_documents_upload (upload_id),
                                  INDEX idx_patent_documents_file_md5 (file_md5),
                                  INDEX idx_patent_documents_user (user_id),
                                  INDEX idx_patent_documents_org_tag (org_tag),
                                  INDEX idx_patent_documents_publication_no (publication_no),
                                  INDEX idx_patent_documents_application_no (application_no),
                                  INDEX idx_patent_documents_title (title),
                                  INDEX idx_patent_documents_applicant (applicant(255)),
                                  CONSTRAINT fk_patent_documents_upload FOREIGN KEY (upload_id) REFERENCES file_upload(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专利结构化文档表';

CREATE TABLE patent_sections (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '专利章节主键',
                                 patent_id BIGINT NOT NULL COMMENT '关联 patent_documents.id',
                                 section_type VARCHAR(50) NOT NULL COMMENT '章节类型：ABSTRACT/CLAIMS/TECHNICAL_FIELD/BACKGROUND/SUMMARY/DRAWING_DESC/EMBODIMENT等',
                                 section_title VARCHAR(255) DEFAULT NULL COMMENT '章节标题',
                                 section_order INT NOT NULL DEFAULT 0 COMMENT '章节顺序',
                                 text_content LONGTEXT COMMENT '章节文本',
                                 page_start INT DEFAULT NULL COMMENT '起始页码',
                                 page_end INT DEFAULT NULL COMMENT '结束页码',
                                 anchor_text VARCHAR(512) DEFAULT NULL COMMENT '页内定位锚点文本',
                                 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 INDEX idx_patent_sections_patent (patent_id),
                                 INDEX idx_patent_sections_type (section_type),
                                 UNIQUE KEY uk_patent_sections_order (patent_id, section_order),
                                 CONSTRAINT fk_patent_sections_patent FOREIGN KEY (patent_id) REFERENCES patent_documents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专利章节表';

CREATE TABLE patent_claims (
                               id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '权利要求主键',
                               patent_id BIGINT NOT NULL COMMENT '关联 patent_documents.id',
                               claim_no INT NOT NULL COMMENT '权利要求编号',
                               text_content LONGTEXT NOT NULL COMMENT '权利要求文本',
                               is_independent BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否独立权利要求',
                               depends_on_claim_no INT DEFAULT NULL COMMENT '依赖的权利要求编号',
                               technical_features_json JSON COMMENT '技术特征拆解 JSON',
                               page_number INT DEFAULT NULL COMMENT 'PDF 页码',
                               anchor_text VARCHAR(512) DEFAULT NULL COMMENT '页内定位锚点文本',
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               UNIQUE KEY uk_patent_claims_no (patent_id, claim_no),
                               INDEX idx_patent_claims_patent (patent_id),
                               INDEX idx_patent_claims_independent (is_independent),
                               INDEX idx_patent_claims_depends_on (depends_on_claim_no),
                               CONSTRAINT fk_patent_claims_patent FOREIGN KEY (patent_id) REFERENCES patent_documents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专利权利要求表';

CREATE TABLE patent_chunks (
                               id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '专利检索块主键',
                               patent_id BIGINT NOT NULL COMMENT '关联 patent_documents.id',
                               source_type VARCHAR(50) NOT NULL COMMENT '来源类型：BIBLIOGRAPHIC/ABSTRACT/CLAIM/DESCRIPTION/DRAWING等',
                               source_id BIGINT DEFAULT NULL COMMENT '来源表主键，如 patent_claims.id 或 patent_sections.id',
                               chunk_no INT NOT NULL COMMENT '同一专利内的检索块序号',
                               text_content LONGTEXT NOT NULL COMMENT '检索块文本',
                               page_number INT DEFAULT NULL COMMENT 'PDF 页码',
                               anchor_text VARCHAR(512) DEFAULT NULL COMMENT '页内定位锚点文本',
                               section_path VARCHAR(500) DEFAULT NULL COMMENT '章节路径',
                               claim_no INT DEFAULT NULL COMMENT '对应权利要求编号',
                               is_independent_claim BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否独立权利要求块',
                               token_count INT DEFAULT NULL COMMENT 'token 数',
                               model_version VARCHAR(32) DEFAULT NULL COMMENT '向量模型版本',
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               UNIQUE KEY uk_patent_chunks_no (patent_id, chunk_no),
                               INDEX idx_patent_chunks_patent (patent_id),
                               INDEX idx_patent_chunks_source (source_type, source_id),
                               INDEX idx_patent_chunks_claim_no (claim_no),
                               INDEX idx_patent_chunks_independent_claim (is_independent_claim),
                               CONSTRAINT fk_patent_chunks_patent FOREIGN KEY (patent_id) REFERENCES patent_documents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专利检索块表';

CREATE TABLE rate_limit_configs (
                                    config_key VARCHAR(64) PRIMARY KEY COMMENT '限流配置键',
                                    single_max INT DEFAULT NULL COMMENT '单窗口最大次数',
                                    single_window_seconds BIGINT DEFAULT NULL COMMENT '单窗口秒数',
                                    minute_max BIGINT DEFAULT NULL COMMENT '分钟窗口最大值',
                                    minute_window_seconds BIGINT DEFAULT NULL COMMENT '分钟窗口秒数',
                                    day_max BIGINT DEFAULT NULL COMMENT '日窗口最大值',
                                    day_window_seconds BIGINT DEFAULT NULL COMMENT '日窗口秒数',
                                    updated_by VARCHAR(255) NOT NULL COMMENT '最后更新人',
                                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时限流配置表';

CREATE TABLE model_provider_configs (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '模型配置主键',
                                        config_scope VARCHAR(32) NOT NULL COMMENT '作用域：llm / embedding',
                                        provider_code VARCHAR(64) NOT NULL COMMENT 'provider 标识',
                                        display_name VARCHAR(128) NOT NULL COMMENT '展示名称',
                                        api_style VARCHAR(64) NOT NULL COMMENT '协议风格',
                                        api_base_url VARCHAR(512) NOT NULL COMMENT 'API 基础地址',
                                        model_name VARCHAR(255) NOT NULL COMMENT '模型名称',
                                        api_key_ciphertext VARCHAR(2048) DEFAULT NULL COMMENT '加密后的 API Key',
                                        embedding_dimension INT DEFAULT NULL COMMENT 'Embedding 维度',
                                        enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
                                        active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否当前激活',
                                        updated_by VARCHAR(255) NOT NULL COMMENT '最后更新人',
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        UNIQUE KEY uk_model_provider_scope_code (config_scope, provider_code),
                                        KEY idx_model_provider_scope (config_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时模型 Provider 配置表';

-- 充值套餐表
CREATE TABLE recharge_packages (
                                   id INT AUTO_INCREMENT PRIMARY KEY COMMENT '套餐 ID（自增主键）',
                                   package_name VARCHAR(128) NOT NULL COMMENT '套餐名称',
                                   package_price BIGINT NOT NULL COMMENT '套餐价格，单位分',
                                   package_desc TEXT COMMENT '套餐描述',
                                   package_benefit TEXT COMMENT '套餐权益',
                                   llm_token INT NOT NULL COMMENT 'LLM token 数量',
                                   embedding_token INT NOT NULL COMMENT 'Embedding token 数量',
                                   sort_order INT NOT NULL DEFAULT 10 COMMENT '排序顺序（数字越小越靠前）',
                                   enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
                                   deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除',
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值套餐表';

-- 充值订单表
CREATE TABLE recharge_orders (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单 ID',
                                 trade_no VARCHAR(128) NOT NULL UNIQUE COMMENT '业务单号（外部系统唯一）',
                                 user_id VARCHAR(64) NOT NULL COMMENT '用户 ID（关联 users 表）',
                                 package_id INT NOT NULL COMMENT '套餐 ID（如果是自定义充值，则为 0）',
                                 amount BIGINT NOT NULL COMMENT '订单金额，单位分',
                                 llm_token INT NOT NULL COMMENT 'LLM token 数量',
                                 embedding_token INT NOT NULL COMMENT 'Embedding token 数量',
                                 wx_transaction_id VARCHAR(64) NOT NULL COMMENT '微信交易流水号',
                                 status ENUM('NOT_PAY', 'PAYING', 'SUCCEED', 'FAIL', 'CANCELLED') NOT NULL DEFAULT 'NOT_PAY' COMMENT '订单状态',
                                 description VARCHAR(255) COMMENT '订单描述',
                                 pay_time TIMESTAMP NULL COMMENT '支付成功时间',
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 INDEX idx_trade_no (trade_no),
                                 INDEX idx_user_id (user_id),
                                 INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值订单表';

-- 初始化充值套餐数据
-- 说明：
-- 1. 保留 1 分钱内部基准套餐，用于自定义充值时按比例折算 Token，不对前台用户展示。
-- 2. 默认三档套餐基于 2026-03-20 的 DeepSeek / 阿里百炼官方价格做保守估算，兼顾吸引力和利润空间。
INSERT INTO recharge_packages (package_name, package_price, package_desc, package_benefit, llm_token, embedding_token, sort_order, enabled)
VALUES
    ('内部基准', 1, '自定义充值折算基准，不对外展示', 'LLM Token: 2,500\nEmbedding Token: 1,000', 2500, 1000, 999, TRUE),
    ('体验版', 990, '适合轻度体验、日常问答和少量知识库上传。', 'LLM Token：250 万\nEmbedding Token：100 万\n支持微信支付充值\n余额到账后可直接使用', 2500000, 1000000, 10, TRUE),
    ('进阶版', 1990, '适合持续问答、资料整理和中等规模知识库构建。', 'LLM Token：550 万\nEmbedding Token：250 万\n支持微信支付充值\n余额到账后可直接使用', 5500000, 2500000, 20, TRUE),
    ('旗舰版', 4990, '适合高频问答、团队共享资料和较大规模知识库场景。', 'LLM Token：1400 万\nEmbedding Token：600 万\n支持微信支付充值\n余额到账后可直接使用', 14000000, 6000000, 30, TRUE);


-- 创建用户 Token 变动记录表
CREATE TABLE IF NOT EXISTS `user_token_record` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
                               `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
                               `record_date` DATE NOT NULL COMMENT '记录日期（按天统计）',
                               `token_type` VARCHAR(20) NOT NULL COMMENT 'Token 类型：LLM/EMBEDDING',
                               `change_type` VARCHAR(20) NOT NULL COMMENT '变动类型：INCREASE/CONSUME',
                                `request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '请求次数（一次充值或对话可能包含多次 API 请求）'
                               `amount` BIGINT NOT NULL COMMENT '变动数量',
                               `balance_before` BIGINT DEFAULT NULL COMMENT '变动前的余额',
                               `balance_after` BIGINT DEFAULT NULL COMMENT '变动后的余额',
                               `reason` VARCHAR(500) DEFAULT NULL COMMENT '变动原因描述',
                               `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注信息（订单号、对话 ID 等）',
                               `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               PRIMARY KEY (`id`),
                               INDEX `idx_user_date` (`user_id`, `record_date`),
                               INDEX `idx_record_date` (`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 Token 变动记录表';


CREATE TABLE IF NOT EXISTS `user_daily_chat_count` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
    `record_date` DATE NOT NULL COMMENT '记录日期',
    `chat_request_count` BIGINT(20) NOT NULL DEFAULT 0 COMMENT '对话请求次数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `record_date`) COMMENT '用户 + 日期唯一索引',
    INDEX `idx_record_date` (`record_date`) COMMENT '按日期查询索引'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日对话次数记录表';
