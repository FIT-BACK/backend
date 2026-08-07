package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.config.LoginAttemptProperties;
import com.fitback.backend.domain.member.entity.LoginAttempt;
import com.fitback.backend.domain.member.repository.LoginAttemptRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.util.HmacUtil;
import com.fitback.backend.global.util.LowercaseNormalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoginAttemptService {

    // 탈퇴 이메일 차단과 해시 용도를 분리하며 변경 시 기존 실패 기록을 조회할 수 없음
    private static final String HASH_CONTEXT = "login-attempt:";
    // 동일 이메일 잠금 경합 시 요청 스레드가 장시간 대기하지 않도록 제한
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final LoginAttemptRepository loginAttemptRepository;
    private final LoginAttemptProperties properties;
    private final HmacUtil hmacUtil;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public LoginAttemptService(
            LoginAttemptRepository loginAttemptRepository,
            LoginAttemptProperties properties,
            HmacUtil hmacUtil,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.properties = properties;
        this.hmacUtil = hmacUtil;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // AuthService가 인증 예외로 롤백되어도 실패 기록과 만료 처리는 별도로 확정
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        this.transactionTemplate.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    // 비밀번호 검사 전에 잠금과 실패 기록 만료 여부 확인
    public void assertLoginAllowed(String email) {
        String emailHash = hashEmail(email);
        boolean locked = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            // 잠금 상태 확인과 만료 행 삭제가 동시에 실행되지 않도록 행 잠금
            LoginAttempt attempt = loginAttemptRepository
                    .findByEmailHashForUpdate(emailHash)
                    .orElse(null);
            // 실패 이력이 없는 이메일은 회원 조회와 비밀번호 검증 진행
            if (attempt == null) {
                return false;
            }
            // 잠금 중에는 회원 조회와 비밀번호 검증 없이 즉시 차단
            if (attempt.isLocked(now)) {
                return true;
            }

            // 만료된 잠금이나 실패 기록은 현재 요청부터 새로 집계
            if (attempt.getLockedUntil() != null
                    || attempt.isFailureExpired(now, properties.failureResetDuration())) {
                loginAttemptRepository.delete(attempt);
            }
            return false;
        }));

        // 트랜잭션이 종료된 뒤 예외를 던져 만료 행 삭제가 롤백되지 않도록 처리
        if (locked) {
            throw new BusinessException(ErrorCode.LOGIN_ATTEMPT_LOCKED);
        }
    }

    // 인증 실패 예외와 관계없이 별도 트랜잭션에서 실패 횟수 확정
    public boolean recordFailure(String email) {
        String emailHash = hashEmail(email);
        try {
            return recordFailureInNewTransaction(emailHash);
        } catch (DataIntegrityViolationException exception) {
            // 최초 행을 동시에 생성한 경우 유니크 충돌 후 기존 행 갱신 재시도
            return recordFailureInNewTransaction(emailHash);
        }
    }

    @Transactional
    public void clear(String email) {
        // 성공한 인증 관련 트랜잭션과 함께 커밋되도록 기본 전파 속성 사용
        loginAttemptRepository.deleteByEmailHash(hashEmail(email));
    }

    @Transactional
    public int deleteStaleAttempts() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 보관 기간 변경 위치: app.login-attempt.retention-duration
        return loginAttemptRepository.deleteStaleAttempts(
                now.minus(properties.retentionDuration()),
                now
        );
    }

    private boolean recordFailureInNewTransaction(String emailHash) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            // 기존 행은 비관적 락으로 조회해 동시 실패 횟수 유실 방지
            LoginAttempt attempt = loginAttemptRepository
                    .findByEmailHashForUpdate(emailHash)
                    .orElse(null);

            if (attempt == null) {
                // 첫 실패부터 maxFailures와 lockDuration 정책을 적용해 행 생성
                LoginAttempt newAttempt = LoginAttempt.create(
                        emailHash,
                        now,
                        properties.maxFailures(),
                        properties.lockDuration()
                );
                loginAttemptRepository.saveAndFlush(newAttempt);
                return newAttempt.isLocked(now);
            }

            // 이후 실패는 엔티티에서 초기화·증가·잠금 전환을 한 번에 처리
            return attempt.recordFailure(
                    now,
                    properties.maxFailures(),
                    properties.failureResetDuration(),
                    properties.lockDuration()
            );
        }));
    }

    // 탈퇴 이메일 차단 해시와 결과가 겹치지 않도록 용도 문자열 포함
    private String hashEmail(String email) {
        String normalizedEmail = LowercaseNormalizer.normalize(email);
        return hmacUtil.hashHex(HASH_CONTEXT + normalizedEmail);
    }
}
