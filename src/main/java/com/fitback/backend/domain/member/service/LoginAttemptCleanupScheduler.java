package com.fitback.backend.domain.member.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginAttemptCleanupScheduler {

    private final LoginAttemptService loginAttemptService;

    // 실패 횟수 초기화가 아닌 다시 요청되지 않는 오래된 행 정리
    // 실행 간격 변경 위치: app.login-attempt.cleanup-interval
    @Scheduled(fixedDelayString = "${app.login-attempt.cleanup-interval}")
    public void cleanupStaleAttempts() {
        loginAttemptService.deleteStaleAttempts();
    }
}
