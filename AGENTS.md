# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

PaiSmart (派聪明) is an enterprise-grade AI knowledge management system built with RAG (Retrieval-Augmented Generation) technology. It provides intelligent document processing and retrieval capabilities using a modern tech stack including Spring Boot, Vue 3, Elasticsearch, and AI services.

### Migration Goal

This repository is being incrementally migrated from a general-purpose RAG knowledge management system into an **AI Patent Search and Examination Assistance System**.

- The migration must be gradual and reversible where possible.
- Do not break existing RAG capabilities unless a task explicitly requires it.
- New patent capabilities should reuse the existing RAG, Elasticsearch, hybrid retrieval, document parsing, vectorization, WebSocket, and REST infrastructure whenever practical.
- Patent-specific modules should improve evidence-grounded retrieval, claim-level analysis, prior-art comparison, claim chart drafting, and examination report assistance without turning model output into final legal conclusions.

### Naming Note

- `PaiSmart` / `派聪明` is the current legacy project name.
- The future product name may change; do not treat the current project name, package prefix, class prefix, page brand copy, or UI wording as permanent product requirements.
- Do not casually rename packages, classes, modules, database tables, API paths, frontend routes, or module directories during ordinary feature work.
- Naming, branding, package-prefix, UI-copy, and route cleanup must be handled as a separate migration task.

## Tech Stack

- **Backend**: Spring Boot 3.4.2, Java 17, JPA/Hibernate, Spring Security (JWT)
- **Frontend**: Vue 3 + TypeScript, Vite, Pinia, Vue Router
- **Data**: MySQL 8.0, Elasticsearch 8.10.0 (vector + keyword search), Redis 7.0
- **Async**: Apache Kafka 3.2.1 for file processing pipeline
- **Storage**: MinIO 8.5.12 (S3-compatible object storage)
- **AI**: DeepSeek API (LLM), DashScope text-embedding-v4 (vectorization)

## RAG Pipeline Architecture

### Document Ingestion (Async)
```
File Upload → MinIO → Kafka (file-processing topic) → FileProcessingConsumer
                                                              ↓
                                                    ParseService (Apache Tika)
                                                              ↓
                                                    VectorizationService (Embedding API)
                                                              ↓
                                                    Elasticsearch (knowledge_base index)
```

### Chat Flow
```
WebSocket /chat/{token} → ChatHandler → HybridSearchService
                                                ↓
                                    Elasticsearch (KNN + keyword hybrid)
                                                ↓
                                    DeepSeek API → Streamed response
```

TODO: `HybridSearchService` is referenced in legacy documentation, but the current repository no longer contains `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`. Current patent retrieval uses `PatentSearchService`; update legacy flow documentation when the general/patent retrieval split is finalized.

### Multi-tenant Security
- Organization tags (`orgTag`) on all tenant-scoped entities
- `OrgTagAuthorizationFilter` enforces data isolation
- Documents have `is_public` flag for org-wide visibility
- WebSocket tokens embed `primaryOrg` and `orgTags`

## Key Files

### Backend
- `src/main/java/com/yizhaoqi/smartpai/SmartPaiApplication.java` - Entry point
- `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java` - Kafka consumer for async file parsing/vectorization
- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java` - WebSocket chat handler
- `src/main/java/com/yizhaoqi/smartpai/service/patent/PatentSearchService.java` - Patent Elasticsearch hybrid search (vector + keyword)
- `src/main/java/com/yizhaoqi/smartpai/service/patent/PatentIngestionService.java` - Patent structured ingestion into MySQL
- `src/main/java/com/yizhaoqi/smartpai/service/patent/PatentVectorizationService.java` - Patent chunk embedding and Elasticsearch indexing
- `src/main/java/com/yizhaoqi/smartpai/config/WebSocketConfig.java` - WebSocket CORS allowed-origins configuration
- `src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java` - Kafka topic names and consumer group config

### Frontend
- `frontend/src/handler/websocket/` - WebSocket client implementation
- `frontend/src/service/` - API client functions (one file per domain)
- `frontend/src/store/` - Pinia stores

## Configuration

### Profile Hierarchy
- `application.yml` - Base config, most settings have environment variable overrides
- `application-dev.yml` - Local dev overrides
- `application-docker.yml` - Docker deployment settings
- `application-prod.yml` - Production settings
- `.env.example` in project root - Copy to `.env` for IDE running (Spring reads it automatically)

### Key Service Ports (from docker-compose.yaml)
| Service | Port |
|---------|------|
| MySQL | 3306 |
| Redis | 6379 |
| Kafka | 9092 |
| Elasticsearch | 9200 |
| MinIO (API) | 19000 |
| MinIO (Console) | 19001 |

### Important Config Notes
- `security.allowed-origins` in `application.yml` controls WebSocket CORS. Must include frontend origin (e.g., `http://localhost:9527`) for WebSocket handshake to succeed
- `elasticsearch.scheme` defaults to `http` (not `https`) when running locally
- Kafka topics (`file-processing`, `vectorization`) are auto-created on startup with 1 partition/replica

## Common Commands

### Backend
```bash
# Run with dev profile (reads .env automatically)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Run specific test
mvn test -Dtest=SomeServiceTest

# Package
mvn clean package
```

### Frontend
```bash
cd frontend && pnpm install
pnpm dev          # Dev server on port 9527
pnpm build        # Production build
pnpm typecheck    # TypeScript check
pnpm lint         # Lint check
```

### Docker Services
```bash
docker compose -f docs/docker-compose.yaml up -d   # Start all infra services
docker compose -f docs/docker-compose.yaml ps      # Check status
```
Legacy `docker-compose` may work if installed, but prefer the modern `docker compose` command for consistency.

## Development Workflow

### Adding a New Feature (Backend)
1. Create entity in `entity/` with JPA annotations
2. Create repository in `repository/`
3. Create service in `service/`
4. Create controller in `controller/`
5. JPA auto-generates DDL on restart (ddl-auto: update)

Production risk note: `ddl-auto: update` is convenient for local development but risky for production schema changes. For production-facing database changes, prefer explicit migration SQL under `docs/databases/` and document rollout/rollback considerations.

### Adding a New API Endpoint
- Follow RESTful conventions
- JWT authentication via `SecurityConfig` filter chain
- Organization-tag-based authorization via `OrgTagAuthorizationFilter`

### WebSocket Debugging
- WebSocket endpoint: `/chat/{token}` where token is JWT
- Check `ChatWebSocketHandler` for message handling logic
- Ensure `security.allowed-origins` includes frontend URL before handshake



## AI Patent System Migration Goal

This repository is being migrated from a general-purpose RAG knowledge management system into an AI Patent Search and Examination Assistance System.

The migration must be incremental. Existing RAG capabilities must remain compatible unless explicitly changed. Prefer extending and specializing the existing RAG stack over replacing it wholesale.

Target capabilities include:

- patent semantic search
- keyword + vector hybrid search
- metadata filtering by applicant, inventor, IPC/CPC, dates, publication number, and application number
- patent document structuring
- claim-level retrieval
- prior art search
- claim chart generation
- novelty analysis assistance
- inventive step / obviousness analysis assistance
- examination report draft generation
- evidence-grounded answers with traceable citations

## Codex Collaboration Rules

- Understand the existing code before editing.
- For complex or architectural tasks, propose a plan before making changes.
- Prefer small, reviewable, reversible changes.
- Do not rewrite unrelated modules.
- Do not remove existing RAG features unless explicitly requested.
- Do not introduce large dependencies without explaining the reason, alternatives, and impact.
- Follow existing code style, package structure, naming conventions, and test patterns.
- After changes, run relevant tests, lint, type checks, or builds when possible.
- If validation commands fail, report the exact failure and suggest how to verify locally.
- If a task touches architecture, database schema, Elasticsearch index mappings, or API design, explain the risk, compatibility impact, and reasonable alternatives before implementation.
- Keep changes scoped to the task. Avoid opportunistic cleanup, package renaming, route changes, table renaming, or UI copy rewrites unless explicitly requested.
- Preserve current directory structure and naming habits unless a dedicated migration task says otherwise.

## Patent Domain Rules

- Do not fabricate patent numbers, publication numbers, application numbers, applicants, inventors, claim numbers, IPC/CPC classes, paragraph numbers, or citation sources.
- Patent search, examination assistance, claim charts, prior-art comparisons, and generated reports must be evidence-grounded and traceable.
- Search results and generated answers must preserve traceable sources.
- Do not present model guesses, semantic similarity, or unstated assumptions as evidence.
- Semantic similarity is not the same as novelty destruction.
- Combining multiple references is not automatically equivalent to lack of inventive step.
- Examination assistance output must distinguish:
  - retrieved evidence
  - model analysis
  - uncertain inference
  - items requiring human review
- The system assists patent search and examination drafting. It does not provide final legal conclusions.

## Patent Data Model Direction

When adding patent-specific features, prefer explicit domain models such as:

- PatentDocument
- PatentMetadata
- PatentChunk
- Claim
- PatentSearchResult
- PriorArtReference
- ClaimChartItem
- ExaminationReport

Patent search results should preserve fields such as:

- source
- score
- chunkId
- patentId
- publicationNumber
- applicationNumber
- title
- applicant
- inventor
- ipc
- cpc
- section
- claimNumber
- paragraphId
- orgTag
- metadata

## Multi-tenant Requirements for Patent Features

All new patent-related entities, indexes, APIs, and search filters must respect existing organization isolation.

- All new patent entities, indexes, APIs, retrieval filters, and report-generation jobs must obey the existing `orgTag` / JWT / `OrgTagAuthorizationFilter` model or an explicitly documented equivalent.
- `PatentDocument`, `PatentChunk`, `PatentSearchResult`, and related DTOs must preserve tenant isolation fields or traceable tenant metadata.
- Patent search must filter by authorized `orgTags`.
- Public/shared patent data must have explicit visibility rules. Do not default shared patent data to globally visible.
- Do not bypass `OrgTagAuthorizationFilter` or existing JWT-based authorization.

## Recommended Migration Phases

- Phase 0: baseline repository understanding and current RAG flow documentation
- Phase 1: patent domain models and metadata extension
- Phase 2: patent document parsing and chunking strategy
- Phase 3: patent search API and hybrid retrieval
- Phase 4: evidence-grounded RAG answers
- Phase 5: claim chart and prior art comparison
- Phase 6: examination assistance report generation
- Phase 7: frontend productization
- Phase 8: evaluation, regression testing, performance, and security
- Phase X: naming, branding, package prefix, UI copy, and route cleanup. This must be a separate task and must not be mixed into core feature development.

## Default Task Output Format

After each task, respond with:

- Goal
- Files changed
- Key design decisions
- Validation commands and results
- Risks and limitations
- Suggested next steps
