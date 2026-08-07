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

    @Modifying(flushAutomatically = true)
    @Query("delete from AnalysisReport report where report.member.id = :memberId")
    void deleteAllByMemberId(@Param("memberId") Long memberId);

    @EntityGraph(attributePaths = {"reportTags", "reportTags.tag"})
    Optional<AnalysisReport> findByIdAndMemberIdAndDeletedAtIsNull(Long reportId, Long memberId);

    //저장 리포트 목록과 마이 클로젯 목록에서 공용, 둘 다 이미지와 태그를 함께 표시
    //createReadUrl 이 objectKey 를 읽으므로 originalImage 필수,
    //customTags 는 엔티티의 @BatchSize 로 묶여 EntityGraph 불필요
    @EntityGraph(attributePaths = {"originalImage", "reportTags", "reportTags.tag"})
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
