package com.fitback.backend.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class NotificationRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private NotificationRepository notificationRepository;

    //테스트 회원 저장
    private Member saveMember(String email, String nickname) {
        Member member = Member.create(
                email,
                nickname,
                "encodedPw",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);
        return member;
    }

    //테스트 알림 저장
    private Notification saveNotification(Member member, NotificationType type) {
        Notification notification = Notification.create(
                member,
                type,
                null,
                null,
                null,
                null,
                "알림 제목",
                "알림 내용"
        );
        entityManager.persist(notification);
        return notification;
    }

    //회원별 알림을 최신 ID순으로 조회
    @Test
    void findByMemberIdOrderByIdDescTest() {
        Member member = saveMember("member@fitback.com", "member");
        Member otherMember = saveMember("other@fitback.com", "other");
        Notification first = saveNotification(member, NotificationType.MARKETING);
        Notification second = saveNotification(member, NotificationType.MARKETING);
        saveNotification(otherMember, NotificationType.MARKETING);
        entityManager.flush();

        List<Notification> notifications =
                notificationRepository.findByMemberIdOrderByIdDesc(
                        member.getId(),
                        PageRequest.of(0, 10)
                );

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(second.getId(), first.getId());
    }

    //cursor보다 작은 알림만 최신 ID순으로 조회
    @Test
    void findByMemberIdAndIdLessThanOrderByIdDescTest() {
        Member member = saveMember("cursor@fitback.com", "cursor");
        Notification first = saveNotification(member, NotificationType.MARKETING);
        Notification second = saveNotification(member, NotificationType.MARKETING);
        saveNotification(member, NotificationType.MARKETING);
        entityManager.flush();

        List<Notification> notifications =
                notificationRepository.findByMemberIdAndIdLessThanOrderByIdDesc(
                        member.getId(),
                        second.getId() + 1,
                        PageRequest.of(0, 10)
                );

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(second.getId(), first.getId());
    }

    //회원별 전체 미읽음 알림 수 조회
    @Test
    void countByMemberIdAndReadAtIsNullTest() {
        Member member = saveMember("unread@fitback.com", "unread");
        Member otherMember = saveMember("other-unread@fitback.com", "otherUnread");
        Notification readNotification =
                saveNotification(member, NotificationType.MARKETING);
        readNotification.markAsRead(LocalDateTime.of(2026, 7, 31, 12, 0));
        saveNotification(member, NotificationType.MARKETING);
        saveNotification(member, NotificationType.MARKETING);
        saveNotification(otherMember, NotificationType.MARKETING);
        entityManager.flush();

        long unreadCount =
                notificationRepository.countByMemberIdAndReadAtIsNull(member.getId());

        assertThat(unreadCount).isEqualTo(2L);
    }

    //전체 읽음 처리는 현재 회원의 미읽음 알림만 변경
    @Test
    void markAllAsReadTest() {
        Member member = saveMember("read-all@fitback.com", "readAll");
        Member otherMember = saveMember("other-read@fitback.com", "otherRead");
        Notification unread = saveNotification(member, NotificationType.MARKETING);
        Notification alreadyRead = saveNotification(member, NotificationType.MARKETING);
        Notification otherUnread = saveNotification(otherMember, NotificationType.MARKETING);
        LocalDateTime previousReadAt = LocalDateTime.of(2026, 7, 30, 12, 0);
        LocalDateTime readAt = LocalDateTime.of(2026, 7, 31, 12, 0);
        alreadyRead.markAsRead(previousReadAt);
        entityManager.flush();

        int updatedRows = notificationRepository.markAllAsRead(member.getId(), readAt);

        Notification updatedUnread =
                notificationRepository.findById(unread.getId()).orElseThrow();
        Notification unchangedRead =
                notificationRepository.findById(alreadyRead.getId()).orElseThrow();
        Notification unchangedOther =
                notificationRepository.findById(otherUnread.getId()).orElseThrow();

        assertThat(updatedRows).isEqualTo(1);
        assertThat(updatedUnread.getReadAt()).isEqualTo(readAt);
        assertThat(unchangedRead.getReadAt()).isEqualTo(previousReadAt);
        assertThat(unchangedOther.getReadAt()).isNull();
    }
}
