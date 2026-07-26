package com.fitback.backend.global.security.handler;

import com.fitback.backend.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OAuthFailureHandlerTest {

    private static final String FRONT_REDIRECT_URI = "http://localhost:3000/oauth/success";

    private final OAuthFailureHandler handler = new OAuthFailureHandler();

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "frontRedirectUri", FRONT_REDIRECT_URI);
    }

    //loadUser에서 우리 ErrorCode를 실은 OAuth2AuthenticationException이면 그 code/message를 쿼리로 리다이렉트
    @Test
    void businessErrorRedirectsWithCodeTest() throws Exception {
        OAuth2Error error = new OAuth2Error(
                ErrorCode.EMAIL_ALREADY_EXISTS.getCode(),
                ErrorCode.EMAIL_ALREADY_EXISTS.getMessage(),
                null);
        AuthenticationException exception = new OAuth2AuthenticationException(error, error.getDescription());

        handler.onAuthenticationFailure(request, response, exception);

        String redirectUrl = captureRedirect();
        assertThat(redirectUrl).startsWith(FRONT_REDIRECT_URI + "?");
        assertThat(redirectUrl).contains("error=" + ErrorCode.EMAIL_ALREADY_EXISTS.getCode());
        assertThat(redirectUrl).contains("message=");
    }

    //OAuth2AuthenticationException이 아닌 일반 예외면 기본 UNAUTHORIZED로 리다이렉트
    @Test
    void genericErrorDefaultsToUnauthorizedTest() throws Exception {
        AuthenticationException exception = new BadCredentialsException("some generic failure");

        handler.onAuthenticationFailure(request, response, exception);

        String redirectUrl = captureRedirect();
        assertThat(redirectUrl).contains("error=" + ErrorCode.UNAUTHORIZED.getCode());
    }

    //한글 message가 URL 인코딩되어 리다이렉트 (원문 한글이 그대로 노출되지 않음)
    @Test
    void koreanMessageIsUrlEncodedTest() throws Exception {
        OAuth2Error error = new OAuth2Error(
                ErrorCode.REJOIN_BLOCKED.getCode(),
                ErrorCode.REJOIN_BLOCKED.getMessage(),   //탈퇴 후 30일 동안 재가입할 수 없습니다.
                null);
        AuthenticationException exception = new OAuth2AuthenticationException(error, error.getDescription());

        handler.onAuthenticationFailure(request, response, exception);

        String redirectUrl = captureRedirect();
        //퍼센트 인코딩되어 원문 한글은 URL에 없어야 함
        assertThat(redirectUrl).doesNotContain("탈퇴");
        assertThat(redirectUrl).contains("%");
    }

    //response.sendRedirect로 전달된 URL 캡처
    private String captureRedirect() throws Exception {
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(urlCaptor.capture());
        return urlCaptor.getValue();
    }
}
