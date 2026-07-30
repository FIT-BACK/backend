package com.fitback.backend.domain.notification.dto;

import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationTargetType;
import com.fitback.backend.domain.notification.entity.NotificationType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

//알림 응답 DTO
public class NotificationResponse {

    //알림 설정 응답 dto
    @Builder
    public record NotificationSettingResponse(
            Boolean analysisCompleteEnabled,
            Boolean lookbookLikedEnabled,
            Boolean trendUpdateEnabled,
            Boolean marketingEnabled
    ) {}

    //알림 목록 응답 dto
    @Builder
    public record NotificationListResponse(
            List<NotificationSummaryResponse> items,
            Long unreadCount,
            Long nextCursor,
            Boolean hasNext,
            Integer pageSize
    ) {}

    //알림 목록 항목 응답 dto
    @Builder
    public record NotificationSummaryResponse(
            Long notificationId,
            NotificationType notificationType,
            String title,
            String body,
            NotificationTargetType targetType,
            Long targetId,
            LocalDateTime readAt,
            LocalDateTime createdAt
    ) {}

    //알림 설정 응답 변환
    public static NotificationSettingResponse toNotificationSettingResponse(
            MemberNotificationSetting setting
    ) {
        return NotificationSettingResponse.builder()
                .analysisCompleteEnabled(setting.getAnalysisCompleteEnabled())
                .lookbookLikedEnabled(setting.getLookbookLikedEnabled())
                .trendUpdateEnabled(setting.getTrendUpdateEnabled())
                .marketingEnabled(setting.getMarketingEnabled())
                .build();
    }

    //알림 목록 응답 변환
    public static NotificationListResponse toNotificationListResponse(
            List<Notification> notifications,
            Long unreadCount,
            Long nextCursor,
            Boolean hasNext,
            Integer pageSize
    ) {
        List<NotificationSummaryResponse> items = notifications.stream()
                .map(NotificationResponse::toNotificationSummaryResponse)
                .toList();

        return NotificationListResponse.builder()
                .items(items)
                .unreadCount(unreadCount)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .pageSize(pageSize)
                .build();
    }

    //알림 유형에 맞는 상세 화면 이동 정보 설정
    public static NotificationSummaryResponse toNotificationSummaryResponse(
            Notification notification
    ) {
        NotificationTarget target = resolveTarget(notification);

        return NotificationSummaryResponse.builder()
                .notificationId(notification.getId())
                .notificationType(notification.getNotificationType())
                .title(notification.getTitle())
                .body(notification.getBody())
                .targetType(target.targetType())
                .targetId(target.targetId())
                .readAt(notification.getReadAt())
                .createdAt(notification.getCreatedAt())
                .build();
    }

    private static NotificationTarget resolveTarget(Notification notification) {
        return switch (notification.getNotificationType()) {
            case ANALYSIS_COMPLETE ->
                    new NotificationTarget(
                            NotificationTargetType.ANALYSIS_REPORT,
                            notification.getReportId()
                    );
            case LOOKBOOK_LIKED ->
                    new NotificationTarget(
                            NotificationTargetType.LOOKBOOK,
                            notification.getLookbookId()
                    );
            case TREND_UPDATE ->
                    new NotificationTarget(
                            NotificationTargetType.TREND,
                            notification.getTrendId()
                    );
            case MARKETING -> new NotificationTarget(null, null);
        };
    }

    private record NotificationTarget(
            NotificationTargetType targetType,
            Long targetId
    ) {}
}
