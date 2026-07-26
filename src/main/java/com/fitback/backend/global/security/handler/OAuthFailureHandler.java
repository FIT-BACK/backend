package com.fitback.backend.global.security.handler;

import com.fitback.backend.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class OAuthFailureHandler implements AuthenticationFailureHandler {

    // 프론트 리다이렉트 주소 (성공 핸들러와 동일 설정값)
    @Value("${app.oauth.front-redirect-uri}")
    private String frontRedirectUri;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {

        // 기본값 (OAuth2AuthenticationException 이 아닌 예외 대비)
        String code = ErrorCode.UNAUTHORIZED.getCode();
        String message = ErrorCode.UNAUTHORIZED.getMessage();

        // loadUser에서 우리 ErrorCode를 실어 던진 OAuth2AuthenticationException이면 꺼내기
        if (exception instanceof OAuth2AuthenticationException oauthEx) {
            OAuth2Error error = oauthEx.getError();
            if (error.getErrorCode() != null) {
                code = error.getErrorCode();        // 예: AUTH409_1
            }
            if (error.getDescription() != null) {
                message = error.getDescription();   // 예: 이미 사용 중인 이메일입니다.
            }
        }

        // 성공 흐름과 동일하게 프론트로 리다이렉트하며 에러 정보를 쿼리로 전달
        String redirectUri = UriComponentsBuilder.fromUriString(frontRedirectUri)
                .queryParam("error", code)
                .queryParam("message", message)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        response.sendRedirect(redirectUri);
    }
}
