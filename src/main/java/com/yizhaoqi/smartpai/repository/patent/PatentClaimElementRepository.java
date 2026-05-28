package com.yizhaoqi.smartpai.repository.patent;

import com.yizhaoqi.smartpai.model.patent.PatentClaimElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatentClaimElementRepository extends JpaRepository<PatentClaimElement, Long> {

    List<PatentClaimElement> findByPatentIdOrderByClaimNoAscElementNoAsc(Long patentId);

    List<PatentClaimElement> findByClaimIdOrderByElementNoAsc(Long claimId);

    void deleteByPatentId(Long patentId);
}
