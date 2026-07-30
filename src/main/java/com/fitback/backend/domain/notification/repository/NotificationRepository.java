package com.fitback.backend.domain.notification.repository;

import com.fitback.backend.domain.notification.entity.Notification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    //첫 페이지: 최신 알림부터 조회
    List<Notification> findByMemberIdOrderByIdDesc(Long memberId, Pageable pageable);

    //다음 페이지: cursor보다 작은 알림을 최신순으로 조회
    List<Notification> findByMemberIdAndIdLessThanOrderByIdDesc(
            Long memberId,
            Long cursor,
            Pageable pageable
    );

    //회원이 받은 알림 단건 조회
    Optional<Notification> findByIdAndMemberId(Long notificationId, Long memberId);

    //회원의 전체 미읽음 알림 수
    long countByMemberIdAndReadAtIsNull(Long memberId);

    //아직 읽지 않은 알림만 일괄 읽음 처리
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification notification
            set notification.readAt = :readAt
            where notification.member.id = :memberId
              and notification.readAt is null
            """)
    int markAllAsRead(
            @Param("memberId") Long memberId,
            @Param("readAt") LocalDateTime readAt
    );
}
