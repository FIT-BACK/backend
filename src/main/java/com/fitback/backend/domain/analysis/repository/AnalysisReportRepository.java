package com.fitback.backend.domain.analysis.repository;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {
    long countByMemberId(Long memberId);
}
