package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
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
    private static final String PATENT_CHUNKS_INDEX = "patent_chunks";

    @Autowired
    private ElasticsearchClient esClient;

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

    public long deleteAllPatentChunks() {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(PATENT_CHUNKS_INDEX)
                    .query(q -> q.matchAll(m -> m))
            );
            var response = esClient.deleteByQuery(request);
            long deleted = response.deleted();
            logger.info("清空 Elasticsearch patent_chunks 索引，删除数量={}", deleted);
            return deleted;
        } catch (Exception e) {
            logger.error("清空 Elasticsearch patent_chunks 失败", e);
            throw new RuntimeException("清空专利 ES 失败", e);
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
