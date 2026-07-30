package com.fitback.backend.domain.notification.entity;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    //알림을 받은 회원
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 30)
    private NotificationType notificationType;

    //알림을 발생시킨 회원 ID (시스템 알림은 null)
    @Column(name = "actor_member_id")
    private Long actorMemberId;

    //알림 유형에 따라 연결되는 대상 ID
    @Column(name = "lookbook_id")
    private Long lookbookId;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "trend_id")
    private Long trendId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    private Notification(
            Member member,
            NotificationType notificationType,
            Long actorMemberId,
            Long lookbookId,
            Long reportId,
            Long trendId,
            String title,
            String body
    ) {
        this.member = member;
        this.notificationType = notificationType;
        this.actorMemberId = actorMemberId;
        this.lookbookId = lookbookId;
        this.reportId = reportId;
        this.trendId = trendId;
        this.title = title;
        this.body = body;
    }

    public static Notification create(
            Member member,
            NotificationType notificationType,
            Long actorMemberId,
            Long lookbookId,
            Long reportId,
            Long trendId,
            String title,
            String body
    ) {
        return new Notification(
                member,
                notificationType,
                actorMemberId,
                lookbookId,
                reportId,
                trendId,
                title,
                body
        );
    }

    //이미 읽은 알림은 최초 읽은 시간을 유지
    public void markAsRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }
}
