package com.fitback.backend.domain.member.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "withdrawal_email_block",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_WITHDRAWAL_EMAIL_BLOCK_EMAIL_HASH",
                columnNames = "email_hash"
        ),
        indexes = @Index( //인덱스 생성
                name = "IDX_WITHDRAWAL_EMAIL_BLOCK_BLOCKED_UNTIL",
                columnList = "blocked_until"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawalEmailBlock extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "withdrawal_id")
    private Long id;

    //이메일 원문 대신 HMAC-SHA256 hex 저장
    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    //재가입 차단 종료 시각
    @Column(name = "blocked_until", nullable = false)
    private LocalDateTime blockedUntil;

    private WithdrawalEmailBlock(String emailHash, LocalDateTime blockedUntil) {
        this.emailHash = emailHash;
        this.blockedUntil = blockedUntil;
    }

    public static WithdrawalEmailBlock create(String emailHash, LocalDateTime blockedUntil) {
        return new WithdrawalEmailBlock(emailHash, blockedUntil);
    }

    //동일 이메일 재탈퇴 시 기존 차단 기록 갱신
    public void renew(LocalDateTime blockedUntil) {
        this.blockedUntil = blockedUntil;
    }
}
