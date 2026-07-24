package com.fitback.backend.domain.notification.dto;

import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
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
}
