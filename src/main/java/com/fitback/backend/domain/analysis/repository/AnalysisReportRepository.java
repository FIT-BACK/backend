package com.fitback.backend.domain.analysis.repository;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    boolean existsByOriginalImageIdAndDeletedAtIsNull(String imageId);

    long countByMemberIdAndDeletedAtIsNull(Long memberId);

    // 마이페이지 "분석" 카운트용 — 추천까지 생성된(끝까지 진행한) 리포트만 완료로 집계한다.
    // 업로드만 하고 중간에 이탈한 분석까지 세면 실제로 안 끝난 분석도 완료로 보여서 오해를 준다.
    long countByMemberIdAndDeletedAtIsNullAndRecommendationGeneratedAtIsNotNull(Long memberId);

    @Modifying(flushAutomatically = true)
    @Query("delete from AnalysisReport report where report.member.id = :memberId")
    void deleteAllByMemberId(@Param("memberId") Long memberId);

    @EntityGraph(attributePaths = {"reportTags", "reportTags.tag"})
    Optional<AnalysisReport> findByIdAndMemberIdAndDeletedAtIsNull(Long reportId, Long memberId);

    @EntityGraph(attributePaths = {"reportTags", "reportTags.tag"})
    List<AnalysisReport> findByIdInAndMemberIdAndDeletedAtIsNull(
            List<Long> reportIds,
            Long memberId
    );

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"member", "reportTags", "reportTags.tag"})
    @Query("""
            select report
            from AnalysisReport report
            where report.id = :reportId
              and report.member.id = :memberId
              and report.deletedAt is null
            """)
    Optional<AnalysisReport> findOwnedReportForSave(
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
