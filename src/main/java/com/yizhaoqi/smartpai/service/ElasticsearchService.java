package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.PatentEsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

// Elasticsearch操作封装服务
@Service
public class ElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);
    private static final String KNOWLEDGE_BASE_INDEX = "knowledge_base";
    private static final String PATENT_CHUNKS_INDEX = "patent_chunks";

    @Autowired
    private ElasticsearchClient esClient;

    /**
     * 批量索引文档到Elasticsearch中
     * 通过接收一个EsDocument对象列表，将这些文档批量索引到名为"knowledge_base"的索引中
     * 使用Elasticsearch的Bulk API来执行批量索引操作，以提高索引效率
     *
     * @param documents 文档列表，每个文档都将被索引到Elasticsearch中
     */
    public void bulkIndex(List<EsDocument> documents) {
        try {
            if (documents == null || documents.isEmpty()) {
                logger.info("待索引文档为空，跳过 knowledge_base 写入");
                return;
            }
            logger.info("开始批量索引文档到Elasticsearch，文档数量: {}", documents.size());

            List<BulkOperation> bulkOperations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index(KNOWLEDGE_BASE_INDEX)
                            .id(doc.getId())
                            .document(doc)
                    )))
                    .toList();

            BulkResponse response = esClient.bulk(BulkRequest.of(b -> b.operations(bulkOperations)));
            if (response.errors()) {
                logger.error("批量索引过程中发生错误:");
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("批量索引部分失败，请检查日志");
            }

            logger.info("批量索引成功完成，文档数量: {}", documents.size());
        } catch (Exception e) {
            logger.error("批量索引失败，文档数量: {}", documents != null ? documents.size() : 0, e);
            throw new RuntimeException("批量索引失败", e);
        }
    }

    public void bulkIndexPatentChunks(List<PatentEsDocument> documents) {
        try {
            if (documents == null || documents.isEmpty()) {
                logger.info("待索引专利文档为空，跳过 patent_chunks 写入");
                return;
            }
            logger.info("开始批量索引专利文档到Elasticsearch，文档数量: {}", documents.size());

            List<BulkOperation> bulkOperations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index(PATENT_CHUNKS_INDEX)
                            .id(doc.getId())
                            .document(doc)
                    )))
                    .toList();

            BulkResponse response = esClient.bulk(BulkRequest.of(b -> b.operations(bulkOperations)));
            if (response.errors()) {
                logger.error("专利文档批量索引过程中发生错误:");
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("专利文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("专利文档批量索引部分失败，请检查日志");
            }

            logger.info("专利文档批量索引成功完成，文档数量: {}", documents.size());
        } catch (Exception e) {
            logger.error("专利文档批量索引失败，文档数量: {}", documents != null ? documents.size() : 0, e);
            throw new RuntimeException("专利文档批量索引失败", e);
        }
    }

    /**
     * 根据file_md5删除文档
     * @param fileMd5 文件指纹
     * @return 删除的文档数量
     */
    public long deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(KNOWLEDGE_BASE_INDEX)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            var response = esClient.deleteByQuery(request);
            long deleted = response.deleted();
            logger.info("从Elasticsearch删除文档: fileMd5={}, 删除数量={}", fileMd5, deleted);
            return deleted;
        } catch (Exception e) {
            logger.error("从Elasticsearch删除文档失败: fileMd5={}", fileMd5, e);
            throw new RuntimeException("删除文档失败", e);
        }
    }

    public long deleteByFileMd5AndUserId(String fileMd5, String userId) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(KNOWLEDGE_BASE_INDEX)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("fileMd5").value(fileMd5)))
                            .must(m -> m.term(t -> t.field("userId").value(userId)))
                    ))
            );
            var response = esClient.deleteByQuery(request);
            long deleted = response.deleted();
            logger.info("从Elasticsearch删除用户文档: fileMd5={}, userId={}, 删除数量={}", fileMd5, userId, deleted);
            return deleted;
        } catch (Exception e) {
            logger.error("从Elasticsearch删除用户文档失败: fileMd5={}, userId={}", fileMd5, userId, e);
            throw new RuntimeException("删除用户文档失败", e);
        }
    }

    public long countByFileMd5(String fileMd5) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index(KNOWLEDGE_BASE_INDEX)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("统计文档失败", e);
        }
    }

    /**
     * 删除知识库所有文档
     */
    public long deleteAllDocuments() {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(KNOWLEDGE_BASE_INDEX)
                    .query(q -> q.matchAll(m -> m))
            );
            var response = esClient.deleteByQuery(request);
            long deleted = response.deleted();
            logger.info("清空 Elasticsearch knowledge_base 索引，删除数量={}", deleted);
            return deleted;
        } catch (Exception e) {
            logger.error("清空 Elasticsearch 失败", e);
            throw new RuntimeException("清空 ES 失败", e);
        }
    }

    public long deletePatentByPatentId(Long patentId) {
        if (patentId == null) {
            return 0L;
        }
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(PATENT_CHUNKS_INDEX)
                    .query(q -> q.term(t -> t.field("patentId").value(patentId)))
            );
            var response = esClient.deleteByQuery(request);
            long deleted = response.deleted();
            logger.info("从Elasticsearch删除专利文档: patentId={}, 删除数量={}", patentId, deleted);
            return deleted;
        } catch (Exception e) {
            logger.error("从Elasticsearch删除专利文档失败: patentId={}", patentId, e);
            throw new RuntimeException("删除专利文档失败", e);
        }
    }

    public long deletePatentByFileMd5AndUserId(String fileMd5, String userId) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(PATENT_CHUNKS_INDEX)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("fileMd5").value(fileMd5)))
                            .must(m -> m.term(t -> t.field("userId").value(userId)))
                    ))
            );
            var response = esClient.deleteByQuery(request);
            long deleted = response.deleted();
            logger.info("从Elasticsearch删除用户专利文档: fileMd5={}, userId={}, 删除数量={}", fileMd5, userId, deleted);
            return deleted;
        } catch (Exception e) {
            logger.error("从Elasticsearch删除用户专利文档失败: fileMd5={}, userId={}", fileMd5, userId, e);
            throw new RuntimeException("删除用户专利文档失败", e);
        }
    }
}
