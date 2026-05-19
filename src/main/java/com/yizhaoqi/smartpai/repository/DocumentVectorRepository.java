package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5); // 查询某文件的所有分块

    List<DocumentVector> findByFileMd5AndUserIdOrderByChunkIdAsc(String fileMd5, String userId);

    long countByFileMd5(String fileMd5);

    long countByFileMd5AndUserId(String fileMd5, String userId);

    long countByFileMd5AndPageNumberIsNotNull(String fileMd5);
    
    /**
     * 删除指定文件MD5的所有文档向量记录
     * 
     * @param fileMd5 文件MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1 AND user_id = ?2", nativeQuery = true)
    void deleteByFileMd5AndUserId(String fileMd5, String userId);

    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO document_vectors (
                file_md5, chunk_id, text_content, page_number, anchor_text, model_version,
                user_id, org_tag, is_public, section_path, chunk_type, is_key_clause, token_count
            ) VALUES (
                :fileMd5, :chunkId, :textContent, :pageNumber, :anchorText, :modelVersion,
                :userId, :orgTag, :isPublic, :sectionPath, :chunkType, :isKeyClause, :tokenCount
            )
            ON DUPLICATE KEY UPDATE
                text_content = VALUES(text_content),
                page_number = VALUES(page_number),
                anchor_text = VALUES(anchor_text),
                model_version = VALUES(model_version),
                org_tag = VALUES(org_tag),
                is_public = VALUES(is_public),
                section_path = VALUES(section_path),
                chunk_type = VALUES(chunk_type),
                is_key_clause = VALUES(is_key_clause),
                token_count = VALUES(token_count)
            """, nativeQuery = true)
    int upsertChunk(@Param("fileMd5") String fileMd5,
                    @Param("chunkId") Integer chunkId,
                    @Param("textContent") String textContent,
                    @Param("pageNumber") Integer pageNumber,
                    @Param("anchorText") String anchorText,
                    @Param("modelVersion") String modelVersion,
                    @Param("userId") String userId,
                    @Param("orgTag") String orgTag,
                    @Param("isPublic") boolean isPublic,
                    @Param("sectionPath") String sectionPath,
                    @Param("chunkType") String chunkType,
                    @Param("isKeyClause") boolean isKeyClause,
                    @Param("tokenCount") Integer tokenCount);
}
