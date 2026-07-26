package com.fitback.backend.global.security.exception;

import com.fitback.backend.global.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OAuthExceptionFactory {

    //우리 ErrorCode를 OAuth2Error에 실어 FailureHandler로 전달
    public static OAuth2AuthenticationException create(ErrorCode errorCode) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(errorCode.getCode(), errorCode.getMessage(), null),
                errorCode.getMessage());
    }

    public static OAuth2AuthenticationException create(ErrorCode errorCode, Throwable cause) {
        OAuth2AuthenticationException exception = create(errorCode);
        exception.initCause(cause);
        return exception;
    }
}
