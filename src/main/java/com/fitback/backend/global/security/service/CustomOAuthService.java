package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.OAuthMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
//OAuth2 제공자의 사용자 정보 API를 호출하는 기본 구현체 (user-info-uri의 url에 요청 보냄)
public class CustomOAuthService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(
            OAuth2UserRequest userRequest
    ) throws OAuth2AuthenticationException {

        // Spring Security가 인가 코드를 카카오 Access Token으로 교환한 이후,
        // 해당 Access Token을 이용해 카카오 사용자 정보 조회
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 최상단 id → socialUid (getAttribute의 반환 타입을 Long으로 못박아 String.valueOf(char[]) 오추론 방지)
        Long kakaoId = oAuth2User.getAttribute("id");
        String socialUid = String.valueOf(kakaoId);

        // kakao_account.email (필수 동의라 정상 경로엔 오지만 방어)
        Map<String, Object> kakaoAccount = oAuth2User.getAttribute("kakao_account");
        if (kakaoAccount == null || kakaoAccount.get("email") == null) {
            throw oauthException(ErrorCode.SOCIAL_EMAIL_REQUIRED);
        }
        String email = (String) kakaoAccount.get("email");

        Optional<Member> found =
                memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, socialUid);
        boolean isNewMember = found.isEmpty();      // 조회 결과가 비었으면 = 신규
        Member member = found.orElseGet(() -> registerNewKakaoMember(email, socialUid));

        return new OAuthMember(member, oAuth2User.getAttributes(), isNewMember);
    }

    private Member registerNewKakaoMember(String email, String socialUid){
        //같은 email의 기존 계정이 있다면 가입 막기
        if (memberRepository.existsByEmail(email)) {
            throw oauthException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        //임시 닉네임 부여
        String tempNickname;
        do {
            tempNickname = "user_" + UUID.randomUUID().toString().substring(0, 8);
        } while (memberRepository.existsByNickname(tempNickname));

        Member newMember = Member.createSocial(email, tempNickname, LoginProvider.KAKAO, socialUid);
        return memberRepository.save(newMember);
    }

    // 우리 ErrorCode를 OAuth2Error에 실어 던지기 (FailureHandler에서 꺼내 프론트로 전달)
    private OAuth2AuthenticationException oauthException(ErrorCode errorCode) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(errorCode.getCode(), errorCode.getMessage(), null),
                errorCode.getMessage());
    }
}