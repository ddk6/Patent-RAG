package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);

    boolean existsByFileMd5AndChunkIndex(String fileMd5, int chunkIndex);

    @Transactional
    void deleteByFileMd5(String fileMd5);
}
