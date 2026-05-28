package com.yizhaoqi.smartpai.repository.patent;

import com.yizhaoqi.smartpai.model.patent.PatentSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface PatentSectionRepository extends JpaRepository<PatentSection, Long> {

    List<PatentSection> findByPatentIdOrderBySectionOrderAsc(Long patentId);

    List<PatentSection> findByPatentIdAndSectionTypeOrderBySectionOrderAsc(Long patentId, String sectionType);

    @Transactional
    @Modifying
    void deleteByPatentId(Long patentId);
}
