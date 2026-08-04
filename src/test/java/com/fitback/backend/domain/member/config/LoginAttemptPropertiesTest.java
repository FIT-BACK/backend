package com.fitback.backend.domain.member.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoginAttemptPropertiesTest {

    @Test
    void createsPropertiesWithPositivePolicyValues() {
        LoginAttemptProperties properties = properties(5, Duration.ofMinutes(1));

        assertThat(properties.maxFailures()).isEqualTo(5);
        assertThat(properties.failureResetDuration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.lockDuration()).isEqualTo(Duration.ofMinutes(1));
    }

    // 실패 허용 횟수가 0 이하이면 모든 로그인이 즉시 잠길 수 있으므로 설정 단계에서 차단
    @Test
    void rejectsNonPositiveMaxFailures() {
        assertThatThrownBy(() -> properties(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-failures");
    }

    // 시간 정책은 별도 설정값이므로 각 항목이 양수인지 독립적으로 검증
    @Test
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> properties(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure-reset-duration");

        assertThatThrownBy(() -> new LoginAttemptProperties(
                5,
                Duration.ofMinutes(1),
                Duration.ZERO,
                Duration.ofHours(24),
                Duration.ofHours(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-duration");
    }

    private LoginAttemptProperties properties(int maxFailures, Duration resetDuration) {
        return new LoginAttemptProperties(
                maxFailures,
                resetDuration,
                Duration.ofMinutes(1),
                Duration.ofHours(24),
                Duration.ofHours(1)
        );
    }
}
