package com.fitback.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.event.AnalysisCompletedEvent;
import com.fitback.backend.domain.notification.event.LookbookLikedEvent;
import com.fitback.backend.domain.notification.repository.MemberNotificationSettingRepository;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

//AFTER_COMMIT 리스너 배선 검증 (테스트에 @Transactional을 붙이면 커밋이 없어 리스너가 실행되지 않음)
@ActiveProfiles("test")
@SpringBootTest
class NotificationEventListenerTransactionIntegrationTest {

    private static final String MEMBER_EMAIL = "notification-event-listener@fitback.com";
    private static final String MEMBER_NICKNAME = "notification-event-listener";
    private static final Long LOOKBOOK_ID = 9001L;
    private static final Long REPORT_ID = 9002L;
    private static final Long ACTOR_ID = 9003L;
    private static final String ACTOR_NICKNAME = "minji";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MemberNotificationSettingRepository notificationSettingRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    //롤백이 없는 테스트라 생성한 데이터를 직접 정리
    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status ->
                memberRepository.findByEmail(MEMBER_EMAIL).ifPresent(member -> {
                    notificationRepository.deleteAll(findNotifications(member.getId()));
                    notificationSettingRepository.findById(member.getId())
                            .ifPresent(notificationSettingRepository::delete);
                    memberRepository.delete(member);
                })
        );
    }

    @Test
    void createsLookbookLikedNotificationOnlyAfterPublishingTransactionCommits() {
        Long recipientId = createRecipient();
        AtomicLong countBeforeCommit = new AtomicLong();

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new LookbookLikedEvent(
                    LOOKBOOK_ID,
                    recipientId,
                    ACTOR_ID,
                    ACTOR_NICKNAME
            ));
            countBeforeCommit.set(notificationRepository.countByMemberIdAndReadAtIsNull(recipientId));
        });

        //커밋 전에는 알림이 생기지 않아야 함
        assertThat(countBeforeCommit.get()).isZero();
        assertThat(findNotifications(recipientId)).singleElement().satisfies(notification -> {
            assertThat(notification.getNotificationType()).isEqualTo(NotificationType.LOOKBOOK_LIKED);
            assertThat(notification.getActorMemberId()).isEqualTo(ACTOR_ID);
            assertThat(notification.getBody()).contains(ACTOR_NICKNAME);
            assertThat(notification.getLookbookId()).isEqualTo(LOOKBOOK_ID);
            assertThat(notification.getReportId()).isNull();
            assertThat(notification.getTrendId()).isNull();
        });
    }

    @Test
    void createsAnalysisCompletedNotificationOnlyAfterPublishingTransactionCommits() {
        Long recipientId = createRecipient();
        AtomicLong countBeforeCommit = new AtomicLong();

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new AnalysisCompletedEvent(REPORT_ID, recipientId));
            countBeforeCommit.set(notificationRepository.countByMemberIdAndReadAtIsNull(recipientId));
        });

        assertThat(countBeforeCommit.get()).isZero();
        assertThat(findNotifications(recipientId)).singleElement().satisfies(notification -> {
            assertThat(notification.getNotificationType())
                    .isEqualTo(NotificationType.ANALYSIS_COMPLETE);
            assertThat(notification.getActorMemberId()).isNull();
            assertThat(notification.getReportId()).isEqualTo(REPORT_ID);
            assertThat(notification.getLookbookId()).isNull();
            assertThat(notification.getTrendId()).isNull();
        });
    }

    private Long createRecipient() {
        return transactionTemplate.execute(status -> memberRepository.save(Member.create(
                MEMBER_EMAIL,
                MEMBER_NICKNAME,
                "encoded-password",
                LoginProvider.EMAIL
        )).getId());
    }

    private List<Notification> findNotifications(Long memberId) {
        return notificationRepository.findByMemberIdOrderByIdDesc(memberId, PageRequest.of(0, 10));
    }
}
