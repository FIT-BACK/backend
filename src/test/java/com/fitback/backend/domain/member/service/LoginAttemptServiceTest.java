package com.fitback.backend.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.config.LoginAttemptProperties;
import com.fitback.backend.domain.member.entity.LoginAttempt;
import com.fitback.backend.domain.member.repository.LoginAttemptRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.util.HmacUtil;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    private static final String EMAIL_HASH = "a".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

    @Mock
    private LoginAttemptRepository loginAttemptRepository;
    @Mock
    private HmacUtil hmacUtil;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(transactionStatus);
        LoginAttemptProperties properties = new LoginAttemptProperties(
                5,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofHours(24),
                Duration.ofHours(1)
        );
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-05T10:00:00Z"),
                ZoneOffset.UTC
        );
        loginAttemptService = new LoginAttemptService(
                loginAttemptRepository,
                properties,
                hmacUtil,
                clock,
                transactionManager
        );
    }

    // 대소문자와 공백을 정규화하고 탈퇴 해시와 분리된 용도 문자열을 붙여 HMAC 생성
    @Test
    void normalizesEmailAndUsesLoginAttemptHashContext() {
        when(hmacUtil.hashHex("login-attempt:test@fitback.com"))
                .thenReturn(EMAIL_HASH);
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.empty());

        loginAttemptService.assertLoginAllowed("  Test@FITBACK.COM  ");

        verify(hmacUtil).hashHex("login-attempt:test@fitback.com");
    }

    @Test
    void allowsLoginWhenFailureRecordDoesNotExist() {
        stubHash();
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.empty());

        loginAttemptService.assertLoginAllowed("test@fitback.com");

        verify(loginAttemptRepository, never()).delete(any(LoginAttempt.class));
    }

    // 잠금 중에는 회원 조회 전에 429 비즈니스 예외를 반환
    @Test
    void rejectsLoginWhileAttemptIsLocked() {
        stubHash();
        LoginAttempt attempt = lockedAttempt();
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> loginAttemptService.assertLoginAllowed("test@fitback.com"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.LOGIN_ATTEMPT_LOCKED)
                );

        verify(loginAttemptRepository, never()).delete(attempt);
    }

    @Test
    void deletesExpiredFailureRecordBeforeAllowingLogin() {
        stubHash();
        LoginAttempt attempt = LoginAttempt.create(
                EMAIL_HASH,
                NOW.minusMinutes(1),
                5,
                Duration.ofMinutes(1)
        );
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.of(attempt));

        loginAttemptService.assertLoginAllowed("test@fitback.com");

        verify(loginAttemptRepository).delete(attempt);
    }

    @Test
    void firstFailureCreatesAttemptRecord() {
        stubHash();
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.empty());

        boolean locked = loginAttemptService.recordFailure("test@fitback.com");

        assertThat(locked).isFalse();
        ArgumentCaptor<LoginAttempt> attemptCaptor =
                ArgumentCaptor.forClass(LoginAttempt.class);
        verify(loginAttemptRepository).saveAndFlush(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getFailedCount()).isEqualTo(1);
        assertThat(attemptCaptor.getValue().getLastFailedAt()).isEqualTo(NOW);
    }

    @Test
    void existingFailureIncrementsCount() {
        stubHash();
        LoginAttempt attempt = LoginAttempt.create(
                EMAIL_HASH,
                NOW.minusSeconds(10),
                5,
                Duration.ofMinutes(1)
        );
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.of(attempt));

        boolean locked = loginAttemptService.recordFailure("test@fitback.com");

        assertThat(locked).isFalse();
        assertThat(attempt.getFailedCount()).isEqualTo(2);
        assertThat(attempt.getLastFailedAt()).isEqualTo(NOW);
        verify(loginAttemptRepository, never()).saveAndFlush(any());
    }

    // 동일 이메일의 최초 실패 INSERT가 충돌하면 새 트랜잭션에서 기존 행을 다시 잠금 조회
    @Test
    void retriesConcurrentFirstFailureAfterUniqueConstraintCollision() {
        stubHash();
        LoginAttempt concurrentAttempt = LoginAttempt.create(
                EMAIL_HASH,
                NOW.minusSeconds(1),
                5,
                Duration.ofMinutes(1)
        );
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.empty(), Optional.of(concurrentAttempt));
        when(loginAttemptRepository.saveAndFlush(any(LoginAttempt.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate email hash"));

        boolean locked = loginAttemptService.recordFailure("test@fitback.com");

        assertThat(locked).isFalse();
        assertThat(concurrentAttempt.getFailedCount()).isEqualTo(2);
        verify(transactionManager, times(2)).getTransaction(any());
    }

    @Test
    void clearDeletesNormalizedEmailHash() {
        stubHash();

        loginAttemptService.clear("Test@FITBACK.COM");

        verify(loginAttemptRepository).deleteByEmailHash(EMAIL_HASH);
    }

    @Test
    void deletesOnlyAttemptsOlderThanRetentionDuration() {
        when(loginAttemptRepository.deleteStaleAttempts(
                NOW.minusHours(24),
                NOW
        )).thenReturn(3);

        int deletedCount = loginAttemptService.deleteStaleAttempts();

        assertThat(deletedCount).isEqualTo(3);
    }

    @Test
    void configuresNewTransactionWithBoundedTimeout() {
        stubHash();
        when(loginAttemptRepository.findByEmailHashForUpdate(EMAIL_HASH))
                .thenReturn(Optional.empty());

        loginAttemptService.assertLoginAllowed("test@fitback.com");

        ArgumentCaptor<TransactionDefinition> definitionCaptor =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definitionCaptor.getValue().getTimeout()).isEqualTo(5);
    }

    private void stubHash() {
        when(hmacUtil.hashHex("login-attempt:test@fitback.com"))
                .thenReturn(EMAIL_HASH);
    }

    private LoginAttempt lockedAttempt() {
        LoginAttempt attempt = LoginAttempt.create(
                EMAIL_HASH,
                NOW.minusSeconds(10),
                1,
                Duration.ofMinutes(1)
        );
        return attempt;
    }
}
