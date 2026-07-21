package com.fitback.backend.domain.member.repository;

import com.fitback.backend.domain.member.entity.WithdrawnEmailBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface WithdrawnEmailBlockRepository extends JpaRepository<WithdrawnEmailBlock, Long> {

    //재가입 차단 여부 (만료 전 기록이 있으면 차단)
    boolean existsByEmailHashAndBlockedUntilAfter(String emailHash, LocalDateTime now);

    //재탈퇴 시 기존 기록 갱신(upsert)용 조회
    Optional<WithdrawnEmailBlock> findByEmailHash(String emailHash);
}
