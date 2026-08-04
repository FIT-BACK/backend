package com.fitback.backend.domain.member.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 로그인 실패 누적·초기화·잠금·물리 삭제 정책 설정
// 정책값 변경 위치: application-{profile}.yml의 app.login-attempt
@ConfigurationProperties(prefix = "app.login-attempt")
public record LoginAttemptProperties(
        int maxFailures,
        Duration failureResetDuration,
        Duration lockDuration,
        Duration retentionDuration,
        Duration cleanupInterval
) {

    // 잘못된 설정으로 로그인 제한 정책이 비정상 동작하지 않도록 기동 시 검증
    public LoginAttemptProperties {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException(
                    "app.login-attempt.max-failures must be positive"
            );
        }
        validatePositive(failureResetDuration, "failure-reset-duration");
        validatePositive(lockDuration, "lock-duration");
        validatePositive(retentionDuration, "retention-duration");
        validatePositive(cleanupInterval, "cleanup-interval");
    }

    private static void validatePositive(Duration duration, String propertyName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "app.login-attempt." + propertyName + " must be positive"
            );
        }
    }
}
