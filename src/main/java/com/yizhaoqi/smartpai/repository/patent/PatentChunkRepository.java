package com.yizhaoqi.smartpai.repository.patent;

import com.yizhaoqi.smartpai.model.patent.PatentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface PatentChunkRepository extends JpaRepository<PatentChunk, Long> {

    List<PatentChunk> findByPatentIdOrderByChunkNoAsc(Long patentId);

    List<PatentChunk> findByPatentIdAndSourceTypeOrderByChunkNoAsc(Long patentId, String sourceType);

    List<PatentChunk> findByPatentIdAndClaimNoOrderByChunkNoAsc(Long patentId, Integer claimNo);

    @Transactional
    @Modifying
    void deleteByPatentId(Long patentId);
}
