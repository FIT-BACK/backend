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
        indexes = @Index(
                name = "IDX_WITHDRAWAL_EMAIL_BLOCK_BLOCKED_UNTIL",
                columnList = "blocked_until"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnEmailBlock extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "withdrawal_id")
    private Long id;

    //이메일 원문 대신 HMAC-SHA256 hex (개인정보 hard delete 후에도 재가입 차단용)
    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    //재가입 제한 종료 시각 (탈퇴 시각 + 30일)
    @Column(name = "blocked_until", nullable = false)
    private LocalDateTime blockedUntil;

    private WithdrawnEmailBlock(String emailHash, LocalDateTime blockedUntil) {
        this.emailHash = emailHash;
        this.blockedUntil = blockedUntil;
    }

    public static WithdrawnEmailBlock create(String emailHash, LocalDateTime blockedUntil) {
        return new WithdrawnEmailBlock(emailHash, blockedUntil);
    }

    //재탈퇴 시 기존 기록 재사용 (email_hash UNIQUE 충돌 방지 upsert)
    public void renew(LocalDateTime blockedUntil) {
        this.blockedUntil = blockedUntil;
    }
}
