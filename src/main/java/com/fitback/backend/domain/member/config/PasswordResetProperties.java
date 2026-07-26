package com.fitback.backend.domain.member.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        String frontendUrl,
        String senderEmail,
        Duration tokenTtl
) {

    public PasswordResetProperties {
        //비밀번호 재설정 화면 주소를 검증
        if (frontendUrl == null || frontendUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "app.password-reset.frontend-url must not be blank"
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
    }
}
