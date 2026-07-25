package com.fitback.backend.global.security.service;

import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.OAuthMember;
import com.fitback.backend.global.util.LowercaseNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
//OAuth2 제공자의 사용자 정보 API를 호출하는 기본 구현체 (user-info-uri의 url에 요청 보냄)
public class CustomOAuthService extends DefaultOAuth2UserService {

    private final KakaoMemberRegistrationService kakaoMemberRegistrationService;

    @Override
    public OAuth2User loadUser(
            OAuth2UserRequest userRequest
    ) throws OAuth2AuthenticationException {

        // Spring Security가 인가 코드를 카카오 Access Token으로 교환한 이후,
        // 해당 Access Token을 이용해 카카오 사용자 정보 조회
        OAuth2User oAuth2User = loadOAuthUser(userRequest);

        // 최상단 id → socialUid
        Object kakaoId = oAuth2User.getAttribute("id");
        if (kakaoId == null) {
            throw oauthException(ErrorCode.SOCIAL_ID_REQUIRED);
        }
        String socialUid = String.valueOf(kakaoId);

        // kakao_account.email (필수 동의라 정상 경로엔 오지만 방어)
        Map<String, Object> kakaoAccount = oAuth2User.getAttribute("kakao_account");
        if (kakaoAccount == null || kakaoAccount.get("email") == null) {
            throw oauthException(ErrorCode.SOCIAL_EMAIL_REQUIRED);
        }
        String email = LowercaseNormalizer.normalize((String) kakaoAccount.get("email"));

        KakaoMemberRegistrationService.KakaoMemberResult result =
                kakaoMemberRegistrationService.findOrRegister(email, socialUid);

        return new OAuthMember(result.member(), oAuth2User.getAttributes(), result.isNewMember());
    }

    protected OAuth2User loadOAuthUser(OAuth2UserRequest userRequest) {
        return super.loadUser(userRequest);
    }

    // 우리 ErrorCode를 OAuth2Error에 실어 던지기 (FailureHandler에서 꺼내 프론트로 전달)
    static OAuth2AuthenticationException oauthException(ErrorCode errorCode) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(errorCode.getCode(), errorCode.getMessage(), null),
                errorCode.getMessage());
    }
}
