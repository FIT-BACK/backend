package com.fitback.backend.global.security.handler;

import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.service.AuthService;
import com.fitback.backend.global.security.entity.OAuthMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;

    // 프론트 리다이렉트 주소 (설정값)
    @Value("${app.oauth.front-redirect-uri}")
    private String frontRedirectUri;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        // loadUser가 넣어준 principal에서 우리 Member 꺼내기
        OAuthMember principal = (OAuthMember) authentication.getPrincipal();

        // 토큰 발급 + refresh 저장 (트랜잭션은 서비스가 관리)
        MemberResponse.TokenResponse tokens = authService.createOAuthToken(principal.getMember().getId());

        // 프론트 URL에 토큰을 쿼리로 붙여 리다이렉트
        String redirectUri = UriComponentsBuilder.fromUriString(frontRedirectUri)
                .queryParam("accessToken", tokens.accessToken())
                .queryParam("refreshToken", tokens.refreshToken())
                .queryParam("isNewMember", principal.isNewMember())   // ← 추가
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        response.sendRedirect(redirectUri);
    }
}