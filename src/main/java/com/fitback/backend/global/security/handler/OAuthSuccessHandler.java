package com.fitback.backend.global.security.handler;

import com.fitback.backend.global.security.entity.OAuthMember;
import com.fitback.backend.global.security.token.TempTokenPayload;
import com.fitback.backend.global.security.token.TempTokenStore;
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

    private final TempTokenStore tempTokenStore;

    // 프론트 리다이렉트 주소 (설정값)
    @Value("${app.oauth.front-redirect-uri}")
    private String frontRedirectUri;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        // loadUser가 넣어준 principal에서 memberId, isNewMember 꺼내기
        OAuthMember principal = (OAuthMember) authentication.getPrincipal();

        // 실제 JWT는 여기서 발급하지 않고, 교환용 임시토큰만 발급·저장 (실제 토큰 발급은 교환 API에서)
        String tempToken = tempTokenStore.issue(
                new TempTokenPayload(principal.getMember().getId(), principal.isNewMember()));

        // 프론트 URL에 임시토큰만 붙여 리다이렉트 (실제 accessToken/refreshToken은 URL에 노출되지 않음)
        String redirectUri = UriComponentsBuilder.fromUriString(frontRedirectUri)
                .queryParam("tempToken", tempToken)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        response.sendRedirect(redirectUri);
    }
}
