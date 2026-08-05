package com.fitback.backend.domain.member.repository;

import com.fitback.backend.domain.member.entity.LoginAttempt;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    // 동일 이메일의 동시 실패 횟수 갱신을 위한 잠금 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT attempt FROM LoginAttempt attempt WHERE attempt.emailHash = :emailHash")
    Optional<LoginAttempt> findByEmailHashForUpdate(@Param("emailHash") String emailHash);

    // 로그인·회원가입·비밀번호 재설정 성공 시 실패 기록 제거
    @Modifying
    @Query("DELETE FROM LoginAttempt attempt WHERE attempt.emailHash = :emailHash")
    int deleteByEmailHash(@Param("emailHash") String emailHash);

    // 현재 잠금 중이 아닌 오래된 실패 기록 물리 삭제
    @Modifying
    @Query("""
            DELETE FROM LoginAttempt attempt
            WHERE attempt.lastFailedAt < :failedBefore
              AND (attempt.lockedUntil IS NULL OR attempt.lockedUntil <= :now)
            """)
    int deleteStaleAttempts(
            @Param("failedBefore") LocalDateTime failedBefore,
            @Param("now") LocalDateTime now
    );
}
