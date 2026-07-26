package com.fitback.backend.domain.member.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        String frontendUrl,
        String senderEmail,
        Duration tokenTtl,
        Duration requestCooldown
) {

    public PasswordResetProperties {
        //비밀번호 재설정 화면 주소를 검증
        if (frontendUrl == null || frontendUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "app.password-reset.frontend-url must not be blank"
            );
        }

        //비밀번호 재설정 화면 주소가 유효한 HTTP 주소인지 검증
        URI frontendUri;
        try {
            frontendUri = URI.create(frontendUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "app.password-reset.frontend-url must be a valid HTTP(S) URL",
                    exception
            );
        }

        String scheme = frontendUri.getScheme();
        if (!frontendUri.isAbsolute()
                || frontendUri.getHost() == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "app.password-reset.frontend-url must be a valid HTTP(S) URL"
            );
        }

        //비밀번호 재설정 메일 발신 주소 검증
        if (senderEmail == null || senderEmail.isBlank()) {
            throw new IllegalArgumentException(
                    "app.password-reset.sender-email must not be blank"
            );
        }

        //재설정 토큰 만료 시간이 양수인지 검증
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "app.password-reset.token-ttl must be positive"
            );
        }

        //재설정 링크 재요청 대기 시간이 양수인지 검증
        if (requestCooldown == null
                || requestCooldown.isZero()
                || requestCooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "app.password-reset.request-cooldown must be positive"
            );
        }

        //재요청 대기 시간은 토큰 만료 시간보다 짧게 제한
        if (requestCooldown.compareTo(tokenTtl) >= 0) {
            throw new IllegalArgumentException(
                    "app.password-reset.request-cooldown must be shorter than token-ttl"
            );
        }
    }
}
