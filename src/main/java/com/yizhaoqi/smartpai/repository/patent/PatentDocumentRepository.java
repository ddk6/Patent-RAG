package com.yizhaoqi.smartpai.repository.patent;

import com.yizhaoqi.smartpai.model.patent.PatentDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatentDocumentRepository extends JpaRepository<PatentDocument, Long> {

    Optional<PatentDocument> findByUploadId(Long uploadId);

    List<PatentDocument> findByUploadIdIn(Collection<Long> uploadIds);

    Optional<PatentDocument> findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    List<PatentDocument> findByFileMd5(String fileMd5);

    List<PatentDocument> findByUserIdOrderByCreatedAtDesc(String userId);

    List<PatentDocument> findByPublicationNo(String publicationNo);

    List<PatentDocument> findByApplicationNo(String applicationNo);

    @Transactional
    @Modifying
    void deleteByUploadId(Long uploadId);

    @Transactional
    @Modifying
    void deleteByFileMd5AndUserId(String fileMd5, String userId);
}
