package com.fitback.backend.domain.member.entity;

import com.fitback.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "member",
        uniqueConstraints = {
            @UniqueConstraint(name = "UK_MEMBER_EMAIL", columnNames = "email"),
            @UniqueConstraint(name = "UK_MEMBER_NICKNAME", columnNames = "nickname")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    @Column(name = "password", length = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "login_provider", nullable = false, length = 20)
    private LoginProvider loginProvider;

    @Column(name = "profile_image_url", length = 2048)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role = MemberRole.USER;

    @Column(name = "refresh_token", length = 512)
    private String refreshToken;


    private Member(String email, String nickname, String password, LoginProvider loginProvider) {
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.nickname = Objects.requireNonNull(nickname, "nickname must not be null");
        this.password = password;
        this.loginProvider = Objects.requireNonNull(loginProvider, "loginProvider must not be null");
    }

    public static Member create(String email, String nickname, String password, LoginProvider loginProvider) {
        return new Member(email, nickname, password, loginProvider);
    }

    public void changeNickname(String nickname) {
        this.nickname = Objects.requireNonNull(nickname, "nickname must not be null");
    }

    public void changeProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public void changeRole(MemberRole role) {
        this.role = Objects.requireNonNull(role, "role must not be null");
    }

    public void updateRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public void clearRefreshToken() {
        this.refreshToken = null;
    }
}
