package com.fitback.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.dto.NotificationResponse;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationTargetType;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-31T03:00:00Z");
    private static final LocalDateTime FIXED_NOW =
            LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationService notificationService;
    private Member member;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                notificationRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
        );
        member = createTestMember(1L);
    }

    //실제 db를 안 쓰므로 회원 id 강제 세팅
    private Member createTestMember(Long id) {
        Member testMember = Member.create(
                "notification@fitback.com",
                "notification",
                "encodedPw",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(testMember, "id", id);
        return testMember;
    }

    //응답 변환에 필요한 알림 id와 생성 시각 강제 세팅
    private Notification createNotification(
            Long id,
            NotificationType type,
            Long lookbookId,
            Long reportId,
            Long trendId
    ) {
        Notification notification = Notification.create(
                member,
                type,
                null,
                lookbookId,
                reportId,
                trendId,
                "알림 제목",
                "알림 내용"
        );
        ReflectionTestUtils.setField(notification, "id", id);
        ReflectionTestUtils.setField(notification, "createdAt", FIXED_NOW.minusMinutes(id));
        return notification;
    }

    //첫 페이지 - 한 개 더 조회하여 다음 페이지 여부와 cursor 계산
    @Test
    void getNotificationsFirstPageTest() {
        List<Notification> notifications = List.of(
                createNotification(5L, NotificationType.LOOKBOOK_LIKED, 50L, null, null),
                createNotification(4L, NotificationType.LOOKBOOK_LIKED, 40L, null, null),
                createNotification(3L, NotificationType.LOOKBOOK_LIKED, 30L, null, null)
        );
        when(notificationRepository.findByMemberIdOrderByIdDesc(eq(1L), any(Pageable.class)))
                .thenReturn(notifications);
        when(notificationRepository.countByMemberIdAndReadAtIsNull(1L)).thenReturn(7L);

        NotificationResponse.NotificationListResponse response =
                notificationService.getNotifications(1L, null, 2);

        assertThat(response.items())
                .extracting(NotificationResponse.NotificationSummaryResponse::notificationId)
                .containsExactly(5L, 4L);
        assertThat(response.nextCursor()).isEqualTo(4L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(2);
        assertThat(response.unreadCount()).isEqualTo(7L);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository)
                .findByMemberIdOrderByIdDesc(eq(1L), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(3);
    }

    //다음 페이지 - 전달된 cursor보다 작은 알림 조회 메서드 사용
    @Test
    void getNotificationsNextPageTest() {
        Notification notification =
                createNotification(3L, NotificationType.ANALYSIS_COMPLETE, null, 30L, null);
        when(notificationRepository.findByMemberIdAndIdLessThanOrderByIdDesc(
                eq(1L),
                eq(4L),
                any(Pageable.class)
        )).thenReturn(List.of(notification));
        when(notificationRepository.countByMemberIdAndReadAtIsNull(1L)).thenReturn(1L);

        NotificationResponse.NotificationListResponse response =
                notificationService.getNotifications(1L, 4L, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        verify(notificationRepository, never())
                .findByMemberIdOrderByIdDesc(eq(1L), any(Pageable.class));
    }

    //알림 유형별 상세 화면 이동 정보 변환
    @Test
    void getNotificationsMapsTargetsTest() {
        List<Notification> notifications = List.of(
                createNotification(4L, NotificationType.ANALYSIS_COMPLETE, null, 104L, null),
                createNotification(3L, NotificationType.LOOKBOOK_LIKED, 103L, null, null),
                createNotification(2L, NotificationType.TREND_UPDATE, null, null, 102L),
                createNotification(1L, NotificationType.MARKETING, null, null, null)
        );
        when(notificationRepository.findByMemberIdOrderByIdDesc(eq(1L), any(Pageable.class)))
                .thenReturn(notifications);
        when(notificationRepository.countByMemberIdAndReadAtIsNull(1L)).thenReturn(4L);

        NotificationResponse.NotificationListResponse response =
                notificationService.getNotifications(1L, null, 20);

        assertThat(response.items())
                .extracting(NotificationResponse.NotificationSummaryResponse::targetType)
                .containsExactly(
                        NotificationTargetType.ANALYSIS_REPORT,
                        NotificationTargetType.LOOKBOOK,
                        NotificationTargetType.TREND,
                        null
                );
        assertThat(response.items())
                .extracting(NotificationResponse.NotificationSummaryResponse::targetId)
                .containsExactly(104L, 103L, 102L, null);
    }

    //단건 읽음 - 최초 읽은 시각 기록
    @Test
    void markNotificationAsReadTest() {
        Notification notification =
                createNotification(1L, NotificationType.MARKETING, null, null, null);
        when(notificationRepository.findByIdAndMemberId(1L, 1L))
                .thenReturn(Optional.of(notification));

        notificationService.markNotificationAsRead(1L, 1L);

        assertThat(notification.getReadAt()).isEqualTo(FIXED_NOW);
    }

    //단건 읽음 - 이미 읽은 알림이면 기존 시각 유지
    @Test
    void markNotificationAsReadKeepsExistingReadAtTest() {
        Notification notification =
                createNotification(1L, NotificationType.MARKETING, null, null, null);
        LocalDateTime existingReadAt = FIXED_NOW.minusDays(1);
        notification.markAsRead(existingReadAt);
        when(notificationRepository.findByIdAndMemberId(1L, 1L))
                .thenReturn(Optional.of(notification));

        notificationService.markNotificationAsRead(1L, 1L);

        assertThat(notification.getReadAt()).isEqualTo(existingReadAt);
    }

    //단건 조회 - 없거나 다른 회원의 알림이면 동일한 404
    @Test
    void markNotificationAsReadNotFoundTest() {
        when(notificationRepository.findByIdAndMemberId(1L, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markNotificationAsRead(1L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    //전체 읽음 - 현재 회원과 동일한 읽음 시각으로 벌크 처리
    @Test
    void markAllNotificationsAsReadTest() {
        notificationService.markAllNotificationsAsRead(1L);

        verify(notificationRepository).markAllAsRead(1L, FIXED_NOW);
    }

    //알림 삭제 - 소유권 확인 후 하드 삭제
    @Test
    void deleteNotificationTest() {
        Notification notification =
                createNotification(1L, NotificationType.MARKETING, null, null, null);
        when(notificationRepository.findByIdAndMemberId(1L, 1L))
                .thenReturn(Optional.of(notification));

        notificationService.deleteNotification(1L, 1L);

        verify(notificationRepository).delete(notification);
    }

    //페이지 크기 범위를 벗어나면 조회 전 검증 오류
    @Test
    void getNotificationsInvalidPageSizeTest() {
        assertThatThrownBy(() -> notificationService.getNotifications(1L, null, 51))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(notificationRepository, never())
                .findByMemberIdOrderByIdDesc(eq(1L), any(Pageable.class));
    }

    //cursor가 양수가 아니면 조회 전 검증 오류
    @Test
    void getNotificationsInvalidCursorTest() {
        assertThatThrownBy(() -> notificationService.getNotifications(1L, 0L, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(notificationRepository, never())
                .findByMemberIdAndIdLessThanOrderByIdDesc(
                        eq(1L),
                        eq(0L),
                        any(Pageable.class)
                );
    }
}
