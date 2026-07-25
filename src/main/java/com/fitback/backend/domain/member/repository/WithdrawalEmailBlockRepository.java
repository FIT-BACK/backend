package com.fitback.backend.domain.member.repository;

import com.fitback.backend.domain.member.entity.WithdrawalEmailBlock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WithdrawalEmailBlockRepository extends JpaRepository<WithdrawalEmailBlock, Long> {

    //만료되지 않은 재가입 차단 기록 존재 여부
    boolean existsByEmailHashAndBlockedUntilAfter(String emailHash, LocalDateTime now);

    //기존 차단 기록 갱신을 위한 조회
    Optional<WithdrawalEmailBlock> findByEmailHash(String emailHash);

    //만료된 차단 기록 일괄 삭제
    @Modifying(clearAutomatically = true)
    @Query("delete from WithdrawalEmailBlock w where w.blockedUntil < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
