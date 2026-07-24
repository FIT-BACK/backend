package com.fitback.backend.domain.notification.controller;

import com.fitback.backend.domain.notification.dto.NotificationRequest;
import com.fitback.backend.domain.notification.dto.NotificationResponse;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class NotificationController {

    private final NotificationSettingService notificationSettingService;

    @Operation(summary = "알림 설정 조회", description = "(인증필요) 현재 로그인한 회원의 알림 설정을 조회 (없으면 기본값 생성 후 반환)")
    @GetMapping("/v1/members/me/notification-settings")
    public ApiResponse<NotificationResponse.NotificationSettingResponse> getNotificationSettings(
            @AuthenticationPrincipal AuthMember authMember
    ) {
        return ApiResponse.onSuccess(notificationSettingService.getNotificationSettings(authMember));
    }

    @Operation(summary = "알림 설정 수정", description = "(인증필요) 현재 로그인한 회원의 알림 설정을 부분 수정 (전달된 필드만 반영)")
    @PatchMapping("/v1/members/me/notification-settings")
    public ApiResponse<NotificationResponse.NotificationSettingResponse> updateNotificationSettings(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody NotificationRequest.UpdateNotificationSettingRequest request
    ) {
        return ApiResponse.onSuccess(notificationSettingService.updateNotificationSettings(authMember, request));
    }
}
