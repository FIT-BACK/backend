package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.service.RejoinBlockChecker;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.exception.OAuthExceptionFactory;
import com.fitback.backend.global.util.LowercaseNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KakaoMemberRegistrationService {

    private final MemberRepository memberRepository;
    private final RejoinBlockChecker rejoinBlockChecker;
    private final NotificationSettingService notificationSettingService;

    public record KakaoMemberResult(Member member, boolean isNewMember) {}

    @Transactional
    public KakaoMemberResult findOrRegister(String email, String socialUid) {
        return memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, socialUid)
                .map(member -> new KakaoMemberResult(member, false))
                .orElseGet(() -> new KakaoMemberResult(registerNewKakaoMember(email, socialUid), true));
    }

    private Member registerNewKakaoMember(String email, String socialUid) {
        String normalizedEmail = LowercaseNormalizer.normalize(email);

        //같은 email의 기존 계정이 있다면 가입 막기
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw OAuthExceptionFactory.create(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        //탈퇴 후 30일 재가입 차단
        if (rejoinBlockChecker.isRejoinBlocked(normalizedEmail)) {
            throw OAuthExceptionFactory.create(ErrorCode.REJOIN_BLOCKED);
        }

        //임시 닉네임 부여
        String tempNickname;
        do {
            tempNickname = "user_" + UUID.randomUUID().toString().substring(0, 8);
        } while (memberRepository.existsByNickname(tempNickname));

        try {
            Member newMember = Member.createSocial(normalizedEmail, tempNickname, LoginProvider.KAKAO, socialUid);
            Member savedMember = memberRepository.saveAndFlush(newMember);

            //카카오 신규 회원도 기본 알림 설정값 생성
            notificationSettingService.createDefaultSetting(savedMember);

            return savedMember;
        } catch (DataIntegrityViolationException exception) {
            throw duplicateSignupException(exception);
        }
    }

    private OAuth2AuthenticationException duplicateSignupException(DataIntegrityViolationException exception) {
        return OAuthExceptionFactory.create(ErrorCode.EMAIL_ALREADY_EXISTS, exception);
    }
}
