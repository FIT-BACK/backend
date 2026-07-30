package com.fitback.backend.domain.notification.service;

import com.fitback.backend.domain.notification.dto.NotificationResponse;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    //알림 목록 조회
    @Transactional(readOnly = true)
    public NotificationResponse.NotificationListResponse getNotifications(
            Long memberId,
            Long cursor,
            Integer requestedPageSize
    ) {
        int pageSize = validatePageSize(requestedPageSize);
        validateCursor(cursor);

        //다음 페이지 존재 여부 확인을 위해 요청 개수보다 1개 더 조회
        Pageable pageable = PageRequest.of(0, pageSize + 1);

        //cursor가 없으면 첫 페이지, 있으면 해당 알림 ID보다 작은 다음 페이지 조회
        List<Notification> notifications = cursor == null
                ? notificationRepository.findByMemberIdOrderByIdDesc(memberId, pageable)
                : notificationRepository.findByMemberIdAndIdLessThanOrderByIdDesc(
                        memberId,
                        cursor,
                        pageable
                );

        //추가 조회된 알림은 응답에서 제외하고 다음 페이지 존재 여부만 판단
        boolean hasNext = notifications.size() > pageSize;
        List<Notification> currentPage = notifications.stream()
                .limit(pageSize)
                .toList();
        Long nextCursor = hasNext && !currentPage.isEmpty()
                ? currentPage.getLast().getId()
                : null;

        //현재 페이지와 별도로 회원의 전체 미읽음 알림 수 조회
        Long unreadCount = notificationRepository.countByMemberIdAndReadAtIsNull(memberId);

        return NotificationResponse.toNotificationListResponse(
                currentPage,
                unreadCount,
                nextCursor,
                hasNext,
                pageSize
        );
    }

    //알림 단건 읽음 처리
    @Transactional
    public void markNotificationAsRead(Long memberId, Long notificationId) {
        Notification notification = findOwnedNotification(memberId, notificationId);

        //이미 읽은 알림은 엔티티에서 기존 readAt 유지
        notification.markAsRead(LocalDateTime.now(clock));
    }

    //알림 전체 읽음 처리
    @Transactional
    public void markAllNotificationsAsRead(Long memberId) {
        //현재 회원의 읽지 않은 알림만 벌크 업데이트
        notificationRepository.markAllAsRead(memberId, LocalDateTime.now(clock));
    }

    //알림 삭제
    @Transactional
    public void deleteNotification(Long memberId, Long notificationId) {
        //소유권 확인 후 알림 하드 삭제
        Notification notification = findOwnedNotification(memberId, notificationId);
        notificationRepository.delete(notification);
    }

    //본인 알림만 조회하여 다른 회원의 알림 접근 방지
    private Notification findOwnedNotification(Long memberId, Long notificationId) {
        return notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    private int validatePageSize(Integer requestedPageSize) {
        int pageSize = requestedPageSize == null ? DEFAULT_PAGE_SIZE : requestedPageSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return pageSize;
    }

    private void validateCursor(Long cursor) {
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
