package com.fitback.backend.domain.notification.controller;

import com.fitback.backend.domain.notification.dto.NotificationRequest;
import com.fitback.backend.domain.notification.dto.NotificationResponse;
import com.fitback.backend.domain.notification.service.NotificationService;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.CurrentMemberProvider;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class NotificationController {

    private final NotificationSettingService notificationSettingService;
    private final NotificationService notificationService;
    private final CurrentMemberProvider currentMemberProvider;

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

    @Operation(summary = "알림 목록 조회", description = "(인증필요) 현재 로그인한 회원의 알림 목록을 커서 기반으로 조회")
    @GetMapping("/v1/notifications")
    public ApiResponse<NotificationResponse.NotificationListResponse> getNotifications(
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(required = false, defaultValue = "20")
            @Min(1) @Max(50) Integer pageSize
    ) {
        return ApiResponse.onSuccess(notificationService.getNotifications(
                currentMemberProvider.getCurrentMemberId(),
                cursor,
                pageSize
        ));
    }

    @Operation(summary = "알림 읽음 처리", description = "(인증필요) 현재 로그인한 회원의 특정 알림을 읽음 처리")
    @PatchMapping("/v1/notifications/{notificationId}/read")
    public ApiResponse<Void> markNotificationAsRead(
            @PathVariable @Positive Long notificationId
    ) {
        notificationService.markNotificationAsRead(
                currentMemberProvider.getCurrentMemberId(),
                notificationId
        );
        return ApiResponse.onSuccess();
    }

    @Operation(summary = "알림 전체 읽음 처리", description = "(인증필요) 현재 로그인한 회원의 읽지 않은 알림을 모두 읽음 처리")
    @PatchMapping("/v1/notifications/read")
    public ApiResponse<Void> markAllNotificationsAsRead() {
        notificationService.markAllNotificationsAsRead(
                currentMemberProvider.getCurrentMemberId()
        );
        return ApiResponse.onSuccess();
    }

    @Operation(summary = "알림 삭제", description = "(인증필요) 현재 로그인한 회원의 특정 알림을 삭제")
    @DeleteMapping("/v1/notifications/{notificationId}")
    public ApiResponse<Void> deleteNotification(
            @PathVariable @Positive Long notificationId
    ) {
        notificationService.deleteNotification(
                currentMemberProvider.getCurrentMemberId(),
                notificationId
        );
        return ApiResponse.onSuccess();
    }
}
