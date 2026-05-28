package com.yizhaoqi.smartpai.repository.patent;

import com.yizhaoqi.smartpai.model.patent.PatentClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatentClaimRepository extends JpaRepository<PatentClaim, Long> {

    List<PatentClaim> findByPatentIdOrderByClaimNoAsc(Long patentId);

    List<PatentClaim> findByPatentIdAndIsIndependentTrueOrderByClaimNoAsc(Long patentId);

    Optional<PatentClaim> findByPatentIdAndClaimNo(Long patentId, Integer claimNo);

    @Transactional
    @Modifying
    void deleteByPatentId(Long patentId);
}
