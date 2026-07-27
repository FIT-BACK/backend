package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.service.RejoinBlockChecker;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.exception.OAuthExceptionFactory;
import com.fitback.backend.global.util.LowercaseNormalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

@Service
public class KakaoMemberRegistrationService {

    private static final int SIGNUP_TRANSACTION_TIMEOUT_SECONDS = 5;

    private final MemberRepository memberRepository;
    private final RejoinBlockChecker rejoinBlockChecker;
    private final NotificationSettingService notificationSettingService;
    private final TransactionTemplate transactionTemplate;

    public KakaoMemberRegistrationService(
            MemberRepository memberRepository,
            RejoinBlockChecker rejoinBlockChecker,
            NotificationSettingService notificationSettingService,
            PlatformTransactionManager transactionManager
    ) {
        this.memberRepository = memberRepository;
        this.rejoinBlockChecker = rejoinBlockChecker;
        this.notificationSettingService = notificationSettingService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        this.transactionTemplate.setTimeout(SIGNUP_TRANSACTION_TIMEOUT_SECONDS);
    }

    public record KakaoMemberResult(Member member, boolean isNewMember) {}

    //회원을 찾거나 회원이 없으면 신규 회원 등록
    public KakaoMemberResult findOrRegister(String email, String socialUid) {
        String normalizedEmail = LowercaseNormalizer.normalize(email);

        try {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, socialUid)
                            //기존 회원이면 kakaoMemberResult의 isNewMember = false
                            .map(member -> new KakaoMemberResult(member, false))
                            //신규 회원이면 새로은 회원 생성, isNewMember = true
                            .orElseGet(() -> new KakaoMemberResult(
                                    registerNewKakaoMember(normalizedEmail, socialUid),
                                    true
                            ))
            ));
        } catch (DataIntegrityViolationException exception) {
            return resolveConcurrentSignup(normalizedEmail, socialUid, exception);
        }
    }

    private Member registerNewKakaoMember(String normalizedEmail, String socialUid) {
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

        Member newMember = Member.createSocial(normalizedEmail, tempNickname, LoginProvider.KAKAO, socialUid);
        Member savedMember = memberRepository.saveAndFlush(newMember);

        //카카오 신규 회원도 기본 알림 설정값 생성
        notificationSettingService.createDefaultSetting(savedMember);

        return savedMember;
    }

    private KakaoMemberResult resolveConcurrentSignup(
            String normalizedEmail,
            String socialUid,
            DataIntegrityViolationException exception
    ) {
        //가입 트랜잭션이 롤백된 후 같은 카카오 회원이 생성됐는지 다시 확인
        return memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, socialUid)
                .map(member -> new KakaoMemberResult(member, false))
                .orElseGet(() -> {
                    if (memberRepository.existsByEmail(normalizedEmail)) {
                        throw OAuthExceptionFactory.create(ErrorCode.EMAIL_ALREADY_EXISTS, exception);
                    }
                    throw exception;
                });
    }
}
