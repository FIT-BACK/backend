package com.fitback.backend.domain.notification.dto;

//알림 요청 DTO
public class NotificationRequest {

    //알림 설정 수정 (부분 수정: 전달된 필드만 반영, 전체 null 시 서비스에서 400 처리)
    public record UpdateNotificationSettingRequest(
            Boolean analysisCompleteEnabled,
            Boolean lookbookLikedEnabled,
            Boolean trendUpdateEnabled,
            Boolean marketingEnabled
    ) {
        //수정할 필드가 하나도 없는 요청인지 판단
        public boolean isEmpty() {
            return analysisCompleteEnabled == null
                    && lookbookLikedEnabled == null
                    && trendUpdateEnabled == null
                    && marketingEnabled == null;
        }
    }
}
