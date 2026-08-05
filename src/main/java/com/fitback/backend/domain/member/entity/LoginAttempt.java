package com.fitback.backend.domain.member.entity;

import com.fitback.backend.global.util.HmacUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "login_attempt",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_LOGIN_ATTEMPT_EMAIL_HASH",
                columnNames = "email_hash"
        ),
        indexes = @Index(
                name = "IDX_LOGIN_ATTEMPT_LAST_FAILED_AT",
                columnList = "last_failed_at"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "login_attempt_id")
    private Long id;

    // 회원 존재 여부를 노출하지 않도록 이메일 원문과 회원 FK 없이 HMAC 값만 저장
    @Column(name = "email_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String emailHash;

    // 초기화 시간 안에 발생한 연속 로그인 실패 횟수
    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    // 실패 횟수 초기화 기준이 되는 가장 최근 실패 시각
    @Column(name = "last_failed_at", nullable = false)
    private LocalDateTime lastFailedAt;

    // 최대 실패 횟수에 도달했을 때 설정되는 로그인 잠금 종료 시각
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    private LoginAttempt(
            String emailHash,
            LocalDateTime failedAt,
            int maxFailures,
            Duration lockDuration
    ) {
        this.emailHash = HmacUtil.validateHashHex(emailHash);
        this.failedCount = 1;
        this.lastFailedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");
        validateMaxFailures(maxFailures);
        Objects.requireNonNull(lockDuration, "lockDuration must not be null");

        if (failedCount >= maxFailures) {
            this.lockedUntil = failedAt.plus(lockDuration);
        }
    }

    public static LoginAttempt create(
            String emailHash,
            LocalDateTime failedAt,
            int maxFailures,
            Duration lockDuration
    ) {
        return new LoginAttempt(emailHash, failedAt, maxFailures, lockDuration);
    }

    // 잠금 종료 시각과 같거나 지난 경우 잠금 만료 처리
    public boolean isLocked(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    // 마지막 실패 후 초기화 시간이 지나면 기존 실패 횟수 만료
    public boolean isFailureExpired(LocalDateTime now, Duration resetDuration) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(resetDuration, "resetDuration must not be null");
        return !lastFailedAt.plus(resetDuration).isAfter(now);
    }

    // 실패할 때마다 초기화 기준 시각을 갱신하는 슬라이딩 방식
    public boolean recordFailure(
            LocalDateTime now,
            int maxFailures,
            Duration resetDuration,
            Duration lockDuration
    ) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(resetDuration, "resetDuration must not be null");
        Objects.requireNonNull(lockDuration, "lockDuration must not be null");
        validateMaxFailures(maxFailures);

        // 잠금 중 추가 요청으로 잠금 종료 시각을 연장하지 않음
        if (isLocked(now)) {
            return true;
        }
        if (isFailureExpired(now, resetDuration)) {
            failedCount = 0;
            lockedUntil = null;
        }

        failedCount++;
        lastFailedAt = now;
        if (failedCount >= maxFailures) {
            failedCount = maxFailures;
            lockedUntil = now.plus(lockDuration);
            return true;
        }
        return false;
    }

    private static void validateMaxFailures(int maxFailures) {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive");
        }
    }
}
