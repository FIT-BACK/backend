package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.LookbookReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LookbookReportRepository extends JpaRepository<LookbookReport, Long> {

    boolean existsByLookbookIdAndMemberId(Long lookbookId, Long memberId);
}
