package com.fitback.backend.domain.closet.repository;

import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ClosetSaveRepository extends JpaRepository<ClosetSave, Long> {

    //마이 클로젯 목록에 표시 가능한 저장 대상만 집계
    @Query(value = """
            SELECT COUNT(*)
            FROM closet_save closet_save
            WHERE closet_save.member_id = :memberId
              AND (
                  (
                      closet_save.target_type = 'TREND'
                      AND EXISTS (
                          SELECT 1
                          FROM trend_content trend
                          WHERE trend.trend_id = closet_save.target_id
                      )
                  )
                  OR (
                      closet_save.target_type = 'LOOKBOOK'
                      AND EXISTS (
                          SELECT 1
                          FROM lookbook lookbook
                          WHERE lookbook.lookbook_id = closet_save.target_id
                            AND lookbook.deleted_at IS NULL
                      )
                  )
                  OR (
                      closet_save.target_type = 'ANALYSIS_REPORT'
                      AND EXISTS (
                          SELECT 1
                          FROM analysis_report report
                          WHERE report.report_id = closet_save.target_id
                            AND report.member_id = closet_save.member_id
                            AND report.deleted_at IS NULL
                      )
                  )
              )
            """, nativeQuery = true)
    long countDisplayableByMemberId(@Param("memberId") Long memberId);

    Optional<ClosetSave> findByMemberIdAndTargetTypeAndTargetId(
            Long memberId,
            ClosetTargetType targetType,
            Long targetId
    );

    Slice<ClosetSave> findByMemberIdAndTargetTypeOrderByIdDesc(
            Long memberId,
            ClosetTargetType targetType,
            Pageable pageable
    );

    Slice<ClosetSave> findByMemberIdAndTargetTypeAndIdLessThanOrderByIdDesc(
            Long memberId,
            ClosetTargetType targetType,
            Long id,
            Pageable pageable
    );

    List<ClosetSave> findAllByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);

    List<ClosetSave> findAllByMemberIdAndTargetTypeOrderByCreatedAtDescIdDesc(
            Long memberId,
            ClosetTargetType targetType,
            Pageable pageable
    );

    @Query("""
            SELECT closetSave
            FROM ClosetSave closetSave
            WHERE closetSave.member.id = :memberId
              AND (closetSave.createdAt < :cursorCreatedAt
                   OR (closetSave.createdAt = :cursorCreatedAt AND closetSave.id < :cursorId))
            ORDER BY closetSave.createdAt DESC, closetSave.id DESC
            """)
    List<ClosetSave> findNextPage(
            @Param("memberId") Long memberId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Query("""
            SELECT closetSave
            FROM ClosetSave closetSave
            WHERE closetSave.member.id = :memberId
              AND closetSave.targetType = :targetType
              AND (closetSave.createdAt < :cursorCreatedAt
                   OR (closetSave.createdAt = :cursorCreatedAt AND closetSave.id < :cursorId))
            ORDER BY closetSave.createdAt DESC, closetSave.id DESC
            """)
    List<ClosetSave> findNextPageByTargetType(
            @Param("memberId") Long memberId,
            @Param("targetType") ClosetTargetType targetType,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    Optional<ClosetSave> findByIdAndMemberId(Long id, Long memberId);

    boolean existsByMemberIdAndTargetTypeAndTargetId(
            Long memberId,
            ClosetTargetType targetType,
            Long targetId
    );

    @Query("""
            SELECT closetSave.targetId
            FROM ClosetSave closetSave
            WHERE closetSave.member.id = :memberId
              AND closetSave.targetType = :targetType
              AND closetSave.targetId IN :targetIds
            """)
    Set<Long> findSavedTargetIds(
            @Param("memberId") Long memberId,
            @Param("targetType") ClosetTargetType targetType,
            @Param("targetIds") List<Long> targetIds
    );
}
