# KnowLink  — 项目概述与分析

> 本文档对 KnowLink 项目架构进行系统性梳理，涵盖设计决策、技术选型、关键流程及待优化点。

---

## 一、项目定位

KnowLink 是一款面向企业的 AI 知识库管理系统，基于 RAG（Retrieval-Augmented Generation）技术栈构建，提供**智能文档处理**与**语义检索**能力。其核心价值在于：将非结构化文档转化为可检索的知识资产，配合大语言模型实现精准问答。

目标用户场景：
- 专利 PDF 的结构化解析、权利要求检索与技术特征比对
- 企业内部知识库（制度、文档、FAQ）
- 产品技术支持文档检索
- 私有化部署的多租户场景

---

## 二、系统架构总览

```
客户端 (Vue3 Browser)
    │
    │ HTTP REST / WebSocket
    ▼
┌─────────────────────────────────────────────┐
│           Spring Boot Backend               │
│  ┌────────────┐  ┌──────────────────────┐  │
│  │ REST API   │  │  WebSocket Handler   │  │
│  │ Controller │  │  (ChatHandler)        │  │
│  └─────┬──────┘  └──────────┬───────────┘  │
│        │                     │              │
│  ┌─────▼─────────────────────▼───────────┐  │
│  │           Service Layer               │  │
│  │  ChatHandler / DocumentService         │  │
│  │  PatentSearchService / Patent RAG      │  │
│  └─────┬─────────────────────┬───────────┘  │
│        │                     │              │
│  ┌─────▼─────────┐  ┌────────▼────────────┐  │
│  │ MinIO Client  │  │ Kafka Producer       │  │
│  └───────────────┘  └────────┬────────────┘  │
└──────────────────────────────┼──────────────┘
                               │ Kafka Topic
                               ▼
┌──────────────────────────────────────────────┐
│         Kafka Consumer (Async)               │
│  FileProcessingConsumer                       │
│        │                                      │
│        ├──► Patent Direct Parser              │
│        │        │                             │
│        ├──► MinerU API (专利兜底解析)           │
│        │        │                             │
│        ├──► PatentIngestionService            │
│        │        │                             │
│        ├──► PatentVectorizationService        │
│        │        │                             │
│        └──► ElasticsearchService (patent_chunks)│
└──────────────────────────────────────────────┘

┌──────────┐  ┌──────────┐  ┌────────────┐  ┌─────────┐
│  MySQL   │  │  Redis   │  │Elasticsearch│  │  MinIO  │
│ (元数据)  │  │(会话/缓存)│  │(向量+全文)   │  │(文件存储)│
└──────────┘  └──────────┘  └────────────┘  └─────────┘
```

---

## 三、技术栈与选型理由

| 层级 | 技术选型 | 替代方案 | 选型理由 |
|------|---------|---------|---------|
| **运行时** | Java 17 + Spring Boot 3.4 | Python (FastAPI) | 企业级成熟度、AIGC 生态丰富 |
| **前端** | Vue3 + TypeScript + Naive UI | React | 学习成本低、协作团队熟悉 |
| **全文检索** | Elasticsearch 8.10 | Meilisearch / Qdrant | 向量+全文混合搜索成熟方案 |
| **向量引擎** | ES KNN | Qdrant / Milvus / Pinecone | 复用 ES，统一架构 |
| **消息队列** | Apache Kafka 3.2 | RabbitMQ / ActiveMQ | 高吞吐、分区有序、消费者组 |
| **文档解析** | 专利直提服务 + MinerU API | LangChain Loader | 专利字段可结构化抽取，MinerU 作为版面解析兜底 |
| **AI 模型** | DeepSeek Chat | OpenAI GPT / 阿里通义 | 性价比高、国内合规 |
| **向量化** | DashScope Embedding | OpenAI Embedding | 国内可用、中文优化 |
| **文件存储** | MinIO (S3 兼容) | 阿里云 OSS | 私有化部署友好 |
| **缓存** | Redis 7.0 | — | WebSocket 会话存储天然适合 |

---

## 四、核心流程深度分析

### 4.1 专利文档上传与异步处理

```
┌─────────────┐    ┌──────────┐    ┌────────────┐
│  5MB 分片   │───►│  MinIO   │───►│   Kafka    │
│  上传 API   │    │  合并    │    │  异步触发  │
└─────────────┘    └──────────┘    └─────┬──────┘
                                         │
                                         ▼
                  ┌───────────────────────────────┐
                  │ FileProcessingConsumer         │
                  │ 专利直提优先，MinerU 兜底       │
                  └──────────────┬────────────────┘
                                 │
              ┌──────────────────┴──────────────────┐
              ▼                                     ▼
    ┌──────────────────────┐              ┌──────────────────────┐
    │ PatentParserClient   │              │ MinerUService         │
    │ DIRECT_PDF 直提       │              │ fullMd/content/layout │
    └──────────┬───────────┘              └──────────┬───────────┘
               │                                     │
               └──────────────┬──────────────────────┘
                              ▼
                  ┌──────────────────────────────────┐
                  │ PatentIngestionService           │
                  │ patent_documents / claims /      │
                  │ sections / chunks                │
                  └──────────────┬───────────────────┘
                                 ▼
                  ┌──────────────────────────────────┐
                  │ PatentVectorizationService       │
                  │ DashScope text-embedding-v4      │
                  │ 超长 chunk 二次切分保护           │
                  └──────────────┬───────────────────┘
                                 ▼
                  ┌──────────────────────────────────┐
                  │ Elasticsearch patent_chunks       │
                  │ 权利要求 + 说明书 + 元数据检索      │
                  └──────────────────────────────────┘
```

**关键技术点**：

- **分片上传**：前端按 5MB 分片，后端通过 MD5 标识文件，合并时用 MinIO multipart API
- **Redis Bitmap**：追踪每个分片的完成状态，避免重复上传
- **MySQL chunk_info**：持久化分片元数据，重启后可恢复上传状态
- **专利直提快路径**：先用 `DIRECT_PDF` 调专利解析服务，质量不足时回退 MinerU。
- **结构化入库**：专利元数据、权利要求、说明书章节和检索块分别写入 `patent_documents`、`patent_claims`、`patent_sections`、`patent_chunks`。
- **向量化保护**：专利 chunk 向量化前会清洗控制字符，并将超长 chunk 二次切分，避免 DashScope 单条 input 过长导致 HTTP 400。
- **Kafka 幂等性**：通过文件状态机（MERGING → PATENT_STRUCTURING → PATENT_VECTORIZING → COMPLETED / FAILED）保障重复消息可跳过。
- **补偿任务**：专利重试/补偿逻辑可重新投递 Kafka 任务，已入库但 ES 字段变更时需要重新索引。

### 4.2 专利问答检索流程

```
用户 Query
    │
    ▼
┌─────────────────────────────────────┐
│        PatentSearchService          │
│                                     │
│  ┌──────────────┐ ┌──────────────┐ │
│  │ Claim-aware  │ │ Metadata      │ │
│  │ query route  │ │ filters       │ │
│  └──────┬───────┘ └──────┬───────┘ │
│         │                │          │
│         ▼                ▼          │
│  sourceType=CLAIM  applicationNo    │
│  independentClaim  publicationNo    │
│                    fileName/title   │
│                                     │
│  ┌──────────┐     ┌─────────────┐  │
│  │ ES KNN   │     │ ES BM25     │  │
│  │ 向量召回  │     │ 关键词召回  │  │
│  └────┬─────┘     └──────┬──────┘  │
│       └───────┬──────────┘          │
│               ▼                     │
│           RRF-like 融合             │
│               ▼                     │
│          Top-K 证据片段              │
└─────────────────────────────────────┘
    │
    ▼
DeepSeek API (Stream响应)
    │
    ▼
WebSocket → 前端流式展示
```

**关键技术点**：

- **Claim-aware 路由**：问题中出现“权利要求 / 独立权利要求 / claim”时，聊天链路自动优先检索 `sourceType=CLAIM`；独立权利要求问题自动追加 `independentClaim=true`。
- **文件名与申请号召回**：`PatentSearchService` 会解析 `201610481598X.pdf`、`201610481598.X`、`CN...` 等候选标识，并在 BM25/元数据查询中增强相关性。
- **KNN 不依赖 BM25 命中**：关键词无结果时仍保留向量召回，避免语义命中被 BM25 空结果提前截断。
- **可复核证据**：返回上下文包含文件名、题名、公开号、申请号、来源类型、权利要求编号、页码/锚点与原文片段，便于回答中引用。

### 4.3 聊天会话存储

```
WebSocket 消息
    │
    ▼
ChatHandler
    │
    ▼
redisTemplate.opsForList().rightPush()   ← List 类型
    │
    ▼
Redis Key: conversation:{conversationId}
    │
    ▼
前端 /api/v1/users/conversation (GET)
    │
    ▼
redisTemplate.opsForList().range()      ← 必须用 List 类型读取
```

**注意**：

- Redis Key 类型为 List，写用 `rightPush`，读必须用 `range`，不能用 `get`。此前曾因混用 `opsForValue().get()` 导致 WRONGTYPE 错误。
- 流式回答必须由 LLM stream 的 `onComplete` 事件触发保存，不能用“几秒内内容未增长”猜测完成；否则模型短暂停顿时可能把空回复或半截回复写入 Redis，刷新后出现空的 KnowLink 气泡。
- 空白 assistant 响应不会写入历史，会返回错误提示并清理当前 session 状态。

---

## 五、数据模型

### 5.1 核心实体关系

```
User (用户)
  ├── id (Long, PK)
  ├── username
  ├── password (BCrypt)
  └── orgTags (组织标签列表)

Document (文档)
  ├── id (Long, PK)
  ├── fileMd5 (唯一索引)
  ├── fileName
  ├── fileSize
  ├── parseStatus (MERGING / PATENT_STRUCTURING / PATENT_VECTORIZING / COMPLETED / FAILED)
  ├── estimatedChunkCount / actualChunkCount
  ├── orgTag
  └── isPublic

PatentDocument (专利结构化文档)
  ├── id (Long, PK)
  ├── uploadId / fileMd5 / fileName
  ├── applicationNo / publicationNo / title
  ├── applicant / inventor / ipcClassification
  ├── abstractText / mainClaimText
  ├── rawBibliographicJson / rawParserResultJson
  └── parseStatus / parserVersion

PatentClaim (权利要求)
  ├── patentId
  ├── claimNo
  ├── textContent
  ├── independent
  └── dependsOnClaimNo

PatentChunk (专利检索块)
  ├── patentId
  ├── chunkNo
  ├── sourceType (CLAIM / DESCRIPTION / ABSTRACT / BIBLIOGRAPHIC)
  ├── claimNo / sectionPath / pageNumber
  ├── textContent
  └── modelVersion

ChunkInfo (分片信息)
  ├── id (Long, PK)
  ├── fileMd5
  ├── chunkIndex
  ├── chunkSize
  └── status
```

### 5.2 Elasticsearch Index Mapping

当前专利检索主索引为 `patent_chunks`：

```json
{
  "mappings": {
    "properties": {
      "textContent": { "type": "text", "analyzer": "ik_max_word" },
      "vector": { "type": "dense_vector",
        "dims": 2048,
        "index": true,
        "similarity": "cosine" },
      "fileMd5": { "type": "keyword" },
      "fileName": {
        "type": "text",
        "fields": { "keyword": { "type": "keyword" } }
      },
      "sourceType": { "type": "keyword" },
      "claimNo": { "type": "integer" },
      "independentClaim": { "type": "boolean" },
      "publicationNo": { "type": "keyword" },
      "applicationNo": { "type": "keyword" },
      "title": { "type": "text", "analyzer": "ik_max_word" },
      "orgTag": { "type": "keyword" },
      "isPublic": { "type": "boolean" }
    }
  }
}
```

---

## 六、多租户安全设计

### 6.1 Organization Tag 体系

- 每个用户/文档带有 `orgTag`（组织标签），可在多个组织中
- JWT 中嵌入 `primaryOrg` 和 `orgTags[]`
- 所有 ES 查询强制带上 `orgTag` 过滤
- `OrganizationTagAuthorizationFilter` 在请求级别校验数据归属

### 6.2 文档可见性

| 条件 | 可见性 |
|------|--------|
| `is_public = true` 且 `orgTag` 匹配 | 同一组织内所有用户可见 |
| `is_public = false` 且 `orgTag` 匹配 | 仅上传者可见 |
| `orgTag` 不匹配 | 不可见 |

---

## 七、前端架构

```
frontend/
├── src/
│   ├── handler/websocket/     ← WebSocket 客户端封装
│   │   ├── index.ts           ← 连接管理与心跳
│   │   └── message.ts         ← 消息解析与分发
│   ├── service/              ← API 调用层（一域一文件）
│   ├── store/                ← Pinia 状态管理
│   │   ├── modules/
│   │   │   ├── auth.ts       ← 登录/Token 状态
│   │   │   ├── chat.ts       ← 对话状态
│   │   │   └── theme.ts      ← 主题配置
│   │   └── index.ts
│   └── views/
│       ├── chat/             ← 对话页面
│       │   ├── index.vue
│       │   ├── modules/
│       │   │   ├── chat-message.vue
│       │   │   └── input-box.vue
│       │   └── chat.vue
│       └── knowledge-base/    ← 知识库页面
```

**状态管理要点**：
- `chat.ts`：管理对话历史、WebSocket 消息缓存、输入状态
- `auth.ts`：JWT Token 存储，自动刷新机制
- `theme.ts`：Naive UI 主题配置本地持久化

---

## 八、待优化点分析

### 8.1 高优先级

| 问题 | 描述 | 影响 |
|------|------|------|
| **MinerU 解析超时** | API 响应慢时无超时机制，可能阻塞 Consumer | 文件处理卡住 |
| **无重试队列** | Kafka 消费失败后仅记日志，无重试策略 | 部分文件丢失 |
| **ES 索引无版本管理** | 文档更新后直接覆盖，旧的 chunk 未清理 | 存储浪费 |
| **无增量索引** | 文档更新需全量删除后重建 | 性能浪费 |

### 8.2 中优先级

| 问题 | 描述 | 影响 |
|------|------|------|
| **专利证据重排仍偏规则化** | 当前主要依赖 KNN/BM25 融合与字段过滤，尚未加入面向权利要求证据的专用重排 | 复杂问题排序稳定性 |
| **会话无 TTL** | Redis 会话 Key 永不过期 | 内存持续增长 |
| **无链路追踪** | 日志分散，定位问题困难 | 运维成本 |
| **跨组织搜索无隔离测试** | 单元测试覆盖不足 | 安全风险 |

### 8.3 低优先级

| 问题 | 描述 | 影响 |
|------|------|------|
| **WebSocket 重连策略** | 连接断开后重连间隔固定 | 用户体验 |
| **文档解析不支持分页** | 大文件全量加载到内存 | OOM 风险 |
| **无导出功能** | 对话记录无法导出 | 业务需求 |

---

## 九、与同类型开源项目对比

| 维度 | KnowLink | Dify | FastGPT |
|------|---------|------|---------|
| **多租户** | OrgTag + JWT | 团队/工作组 | 命名空间隔离 |
| **文档解析** | 专利直提 + MinerU 兜底 | CSV/JSON 为主 | JSON/TXT |
| **检索证据** | Claim-aware + KNN/BM25 | 通用知识块 | 通用知识块 |
| **架构复杂度** | 中（Kafka 异步） | 低（同步处理） | 中 |
| **私有化友好度** | 高（全部自托管） | 高 | 高 |
| **前端定制化** | Vue3 自研 | React 自研 | React 自研 |

---

## 十、运维与部署

### 10.1 基础设施依赖

```
docs/docker-compose.yaml 定义了：
MySQL 8.0         → 3306
Redis 7.0         → 6379
Kafka 3.2.1       → 9092 (PLAINTEXT)
Elasticsearch 8.10 → 9200 (安全层认证)
MinIO 8.5         → 19000 (API) / 19001 (Console)
```

### 10.2 健康检查

- **MySQL**：JPA 启动时自动检测连接
- **Redis**：`redis-cli ping` 验证
- **ES**：`GET /_cluster/health`
- **Kafka**：`kafka-topics --describe`
- **MinIO**：`mc alias list`

### 10.3 关键配置项

| 配置 | 说明 | 生产建议 |
|------|------|---------|
| `security.allowed-origins` | WebSocket CORS 白名单 | 必须包含前端域名 |
| `elasticsearch.scheme` | 本地默认 http | 生产使用 https |
| `kafka.consumer.group-id` | 消费者组 ID | 多实例部署时必填 |
| `rerank.enabled` | Rerank 功能开关 | 降级备用 |
| `embedding.api.batch-size` | DashScope Embedding 单次请求行数 | `text-embedding-v4` 建议不超过 10 |
| `embedding.api.dimension` | ES dense_vector 维度 | 必须与 `patent_chunks.vector.dims` 一致 |
| `patent.parser.direct-enabled` | 专利直提快路径开关 | 质量不足时回退 MinerU |
| `patent.parser.direct-quality-threshold` | 专利直提质量门禁 | 生产按样本集调参 |

### 10.4 索引变更与重建

- `patent_chunks` mapping 增加字段（例如 `fileName`）后，已存在 ES 文档不会自动补字段。
- 需要重新触发专利重试/补偿任务，或清理并重建相关专利的 ES 索引。
- 如果仅重启后端，新的代码会生效，但旧 ES 文档仍保持原有字段集合。

---

## 附录：关键文件索引

| 文件路径 | 职责 |
|---------|------|
| `src/main/java/.../SmartPaiApplication.java` | 应用启动入口 |
| `src/main/java/.../consumer/FileProcessingConsumer.java` | Kafka 异步文档处理 |
| `src/main/java/.../service/ChatHandler.java` | WebSocket 聊天处理器 |
| `src/main/java/.../service/LlmProviderRouter.java` | 活动 LLM Provider 路由与流式完成回调 |
| `src/main/java/.../service/patent/PatentSearchService.java` | 专利 KNN/BM25/权利要求检索服务 |
| `src/main/java/.../service/patent/PatentVectorizationService.java` | 专利 chunk 向量化与 ES 写入 |
| `src/main/java/.../service/patent/PatentIngestionService.java` | 专利结构化入库 |
| `src/main/java/.../service/patent/PatentParserClient.java` | 专利解析服务客户端 |
| `src/main/java/.../controller/ConversationController.java` | 对话历史 REST API |
| `src/main/java/.../controller/AdminController.java` | 管理员面板 API |
| `src/main/resources/es-mappings/patent_chunks.json` | 专利 ES 索引 mapping |
| `frontend/src/handler/websocket/index.ts` | WebSocket 前端客户端 |
| `docs/docker-compose.yaml` | 基础设施编排 |
