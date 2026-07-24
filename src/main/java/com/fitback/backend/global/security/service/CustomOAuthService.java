package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.service.RejoinBlockChecker;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
//OAuth2 제공자의 사용자 정보 API를 호출하는 기본 구현체 (user-info-uri의 url에 요청 보냄)
public class CustomOAuthService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;
    private final RejoinBlockChecker rejoinBlockChecker;
    private final NotificationSettingService notificationSettingService;

    @Override
    @Transactional
    public OAuth2User loadUser(
            OAuth2UserRequest userRequest
    ) throws OAuth2AuthenticationException {

        // Spring Security가 인가 코드를 카카오 Access Token으로 교환한 이후,
        // 해당 Access Token을 이용해 카카오 사용자 정보 조회
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 최상단 id → socialUid
        Long kakaoId = oAuth2User.getAttribute("id");
        String socialUid = String.valueOf(kakaoId);

        // kakao_account.email (필수 동의라 정상 경로엔 오지만 방어)
        Map<String, Object> kakaoAccount = oAuth2User.getAttribute("kakao_account");
        if (kakaoAccount == null || kakaoAccount.get("email") == null) {
            throw oauthException(ErrorCode.SOCIAL_EMAIL_REQUIRED);
        }
        String email = LowercaseNormalizer.normalize((String) kakaoAccount.get("email"));

        Optional<Member> found =
                memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, socialUid);
        boolean isNewMember = found.isEmpty();      // 조회 결과가 비었으면 신규 계정 생성
        Member member = found.orElseGet(() -> registerNewKakaoMember(email, socialUid));

        return new OAuthMember(member, oAuth2User.getAttributes(), isNewMember);
    }

    private Member registerNewKakaoMember(String email, String socialUid){
        String normalizedEmail = LowercaseNormalizer.normalize(email);

        //같은 email의 기존 계정이 있다면 가입 막기
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw oauthException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        //탈퇴 후 30일 재가입 차단
        if (rejoinBlockChecker.isRejoinBlocked(normalizedEmail)) {
            throw oauthException(ErrorCode.REJOIN_BLOCKED);
        }

        //임시 닉네임 부여
        String tempNickname;
        do {
            tempNickname = "user_" + UUID.randomUUID().toString().substring(0, 8);
        } while (memberRepository.existsByNickname(tempNickname));

        Member newMember = Member.createSocial(normalizedEmail, tempNickname, LoginProvider.KAKAO, socialUid);
        Member savedMember = memberRepository.save(newMember);

        //카카오 신규 회원도 기본 알림 설정값 생성
        notificationSettingService.createDefaultSetting(savedMember);

        return savedMember;
    }

    // 우리 ErrorCode를 OAuth2Error에 실어 던지기 (FailureHandler에서 꺼내 프론트로 전달)
    private OAuth2AuthenticationException oauthException(ErrorCode errorCode) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(errorCode.getCode(), errorCode.getMessage(), null),
                errorCode.getMessage());
    }
}
