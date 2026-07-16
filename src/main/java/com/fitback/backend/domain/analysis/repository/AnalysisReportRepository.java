package com.fitback.backend.domain.analysis.repository;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    @EntityGraph(attributePaths = {"reportTags", "reportTags.tag"})
    Optional<AnalysisReport> findByIdAndMemberId(Long reportId, Long memberId);

    Slice<AnalysisReport> findByMemberIdOrderByIdDesc(Long memberId, Pageable pageable);

    Slice<AnalysisReport> findByMemberIdAndIdLessThanOrderByIdDesc(
            Long memberId,
            Long cursor,
            Pageable pageable
    );
}
