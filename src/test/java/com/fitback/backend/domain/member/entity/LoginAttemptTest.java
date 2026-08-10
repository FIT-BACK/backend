package com.fitback.backend.domain.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class LoginAttemptTest {

    private static final String EMAIL_HASH = "a".repeat(64);
    private static final LocalDateTime FIRST_FAILED_AT =
            LocalDateTime.of(2026, 8, 5, 10, 0);
    private static final Duration POLICY_DURATION = Duration.ofMinutes(1);

    // 최초 실패는 누적 횟수 1회로 생성되며 최대 횟수 전에는 잠그지 않음
    @Test
    void createStoresFirstFailureWithoutLock() {
        LoginAttempt attempt = LoginAttempt.create(
                EMAIL_HASH,
                FIRST_FAILED_AT,
                5,
                POLICY_DURATION
        );

        assertThat(attempt.getFailedCount()).isEqualTo(1);
        assertThat(attempt.getLastFailedAt()).isEqualTo(FIRST_FAILED_AT);
        assertThat(attempt.getLockedUntil()).isNull();
    }

    // 다섯 번째 실패부터 잠그고 설정한 잠금 시간을 종료 시각에 반영
    @Test
    void fifthFailureLocksLoginForConfiguredDuration() {
        LoginAttempt attempt = LoginAttempt.create(
                EMAIL_HASH,
                FIRST_FAILED_AT,
                5,
                POLICY_DURATION
        );

        for (int failure = 2; failure <= 5; failure++) {
            attempt.recordFailure(
                    FIRST_FAILED_AT.plusSeconds(failure),
                    5,
                    POLICY_DURATION,
                    POLICY_DURATION
            );
        }

        assertThat(attempt.getFailedCount()).isEqualTo(5);
        assertThat(attempt.getLockedUntil())
                .isEqualTo(FIRST_FAILED_AT.plusSeconds(5).plusMinutes(1));
    }

    // 잠금 중 재요청으로 잠금 종료 시각이 계속 연장되지 않도록 기존 상태 유지
    @Test
    void failureDuringLockDoesNotExtendLock() {
        LoginAttempt attempt = lockedAttempt();
        LocalDateTime lockedUntil = attempt.getLockedUntil();

        boolean locked = attempt.recordFailure(
                lockedUntil.minusSeconds(10),
                5,
                POLICY_DURATION,
                POLICY_DURATION
        );

        assertThat(locked).isTrue();
        assertThat(attempt.getFailedCount()).isEqualTo(5);
        assertThat(attempt.getLockedUntil()).isEqualTo(lockedUntil);
    }

    // 마지막 실패 후 초기화 시간이 지나면 이번 요청을 새로운 첫 실패로 처리
    @Test
    void failureAfterResetDurationStartsNewCount() {
        LoginAttempt attempt = LoginAttempt.create(
                EMAIL_HASH,
                FIRST_FAILED_AT,
                5,
                POLICY_DURATION
        );
        attempt.recordFailure(
                FIRST_FAILED_AT.plusSeconds(10),
                5,
                POLICY_DURATION,
                POLICY_DURATION
        );

        boolean locked = attempt.recordFailure(
                FIRST_FAILED_AT.plusMinutes(1).plusSeconds(10),
                5,
                POLICY_DURATION,
                POLICY_DURATION
        );

        assertThat(locked).isFalse();
        assertThat(attempt.getFailedCount()).isEqualTo(1);
        assertThat(attempt.getLockedUntil()).isNull();
    }

    // 종료 시각과 같은 순간부터 잠금이 만료되어 정상 검증을 다시 허용
    @Test
    void lockExpiresAtExactBoundary() {
        LoginAttempt attempt = lockedAttempt();

        assertThat(attempt.isLocked(attempt.getLockedUntil().minusNanos(1))).isTrue();
        assertThat(attempt.isLocked(attempt.getLockedUntil())).isFalse();
    }

    @Test
    void createRejectsInvalidPolicyInput() {
        assertThatThrownBy(() -> LoginAttempt.create(
                "invalid-hash",
                FIRST_FAILED_AT,
                5,
                POLICY_DURATION
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LoginAttempt.create(
                EMAIL_HASH,
                FIRST_FAILED_AT,
                0,
                POLICY_DURATION
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private LoginAttempt lockedAttempt() {
        LoginAttempt attempt = LoginAttempt.create(
                EMAIL_HASH,
                FIRST_FAILED_AT,
                5,
                POLICY_DURATION
        );
        for (int failure = 2; failure <= 5; failure++) {
            attempt.recordFailure(
                    FIRST_FAILED_AT.plusSeconds(failure),
                    5,
                    POLICY_DURATION,
                    POLICY_DURATION
            );
        }
        return attempt;
    }
}
