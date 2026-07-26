package com.fitback.backend.domain.member.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(
        name = "password_reset_token",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_PASSWORD_RESET_TOKEN_TOKEN_HASH",
                columnNames = "token_hash"
        ),
        indexes = @Index(
                name = "IDX_PASSWORD_RESET_TOKEN_EXPIRES_AT",
                columnList = "expires_at"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken extends BaseCreateTimeEntity {

    private static final Pattern SHA_256_HEX_PATTERN = Pattern.compile("[0-9a-f]{64}");

    //회원 PK를 토큰 PK로 공유 (회원당 1개)
    @Id
    @Column(name = "member_id")
    private Long memberId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    //재설정 토큰 원문 대신 SHA-256 hex 저장
    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    private PasswordResetToken(Member member, String tokenHash, LocalDateTime expiresAt) {
        this.member = Objects.requireNonNull(member, "member must not be null");
        this.tokenHash = validateTokenHash(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public static PasswordResetToken create(
            Member member,
            String tokenHash,
            LocalDateTime expiresAt
    ) {
        return new PasswordResetToken(member, tokenHash, expiresAt);
    }

    //만료 시각과 같아도 만료된 토큰으로 처리
    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now must not be null"));
    }

    private static String validateTokenHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        if (!SHA_256_HEX_PATTERN.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash must be a SHA-256 hex string");
        }
        return tokenHash;
    }
}
