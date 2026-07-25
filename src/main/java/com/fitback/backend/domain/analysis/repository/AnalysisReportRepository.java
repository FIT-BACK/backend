package com.fitback.backend.domain.analysis.repository;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    boolean existsByOriginalImageId(String imageId);

    long countByMemberIdAndDeletedAtIsNull(Long memberId);

    @EntityGraph(attributePaths = {"reportTags", "reportTags.tag"})
    Optional<AnalysisReport> findByIdAndMemberIdAndDeletedAtIsNull(Long reportId, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "reportTags")
    @Query("""
            select report
            from AnalysisReport report
            where report.id = :reportId
              and report.member.id = :memberId
              and report.deletedAt is null
            """)
    Optional<AnalysisReport> findOwnedReportForRecommendationUpdate(
            @Param("reportId") Long reportId,
            @Param("memberId") Long memberId
    );

    Slice<AnalysisReport> findByMemberIdAndDeletedAtIsNullOrderByIdDesc(
            Long memberId,
            Pageable pageable
    );

    Slice<AnalysisReport> findByMemberIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
            Long memberId,
            Long cursor,
            Pageable pageable
    );
}
