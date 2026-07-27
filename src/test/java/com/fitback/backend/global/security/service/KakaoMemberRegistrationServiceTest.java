package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.service.RejoinBlockChecker;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
import com.fitback.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoMemberRegistrationServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RejoinBlockChecker rejoinBlockChecker;
    @Mock
    private NotificationSettingService notificationSettingService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    @InjectMocks
    private KakaoMemberRegistrationService kakaoMemberRegistrationService;

    //카카오 회원 조회 및 가입 트랜잭션 설정 확인
    @Test
    void findOrRegisterTransactionConfigurationTest() {
        Member existingMember = Member.createSocial(
                "kakao@fitback.com",
                "nick",
                LoginProvider.KAKAO,
                "12345"
        );
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.of(existingMember));

        kakaoMemberRegistrationService.findOrRegister("kakao@fitback.com", "12345");

        ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());

        TransactionDefinition transactionDefinition = transactionDefinitionCaptor.getValue();
        assertThat(transactionDefinition.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transactionDefinition.getTimeout()).isEqualTo(5);
    }

    //카카오 신규 회원 생성 - 회원 저장 후 기본 알림 설정 생성
    @Test
    void registerNewKakaoMemberCreatesDefaultNotificationSettingTest() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.empty());
        when(memberRepository.existsByEmail("kakao@fitback.com")).thenReturn(false);
        when(rejoinBlockChecker.isRejoinBlocked("kakao@fitback.com")).thenReturn(false);
        when(memberRepository.existsByNickname(anyString())).thenReturn(false);
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(inv -> {
            Member member = inv.getArgument(0);
            ReflectionTestUtils.setField(member, "id", 1L);
            return member;
        });

        KakaoMemberRegistrationService.KakaoMemberResult result =
                kakaoMemberRegistrationService.findOrRegister("Kakao@FITBACK.COM", "12345");

        assertThat(result.isNewMember()).isTrue();
        assertThat(result.member().getEmail()).isEqualTo("kakao@fitback.com");
        assertThat(result.member().getLoginProvider()).isEqualTo(LoginProvider.KAKAO);

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(memberCaptor.capture());
        verify(notificationSettingService).createDefaultSetting(result.member());
        assertThat(memberCaptor.getValue().getSocialUid()).isEqualTo("12345");
    }

    //카카오 기존 회원이면 신규 저장 없이 기존 회원 반환
    @Test
    void findExistingKakaoMemberReturnsExistingMemberTest() {
        Member existingMember = Member.createSocial("kakao@fitback.com", "nick", LoginProvider.KAKAO, "12345");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.of(existingMember));

        KakaoMemberRegistrationService.KakaoMemberResult result =
                kakaoMemberRegistrationService.findOrRegister("kakao@fitback.com", "12345");

        assertThat(result.member()).isEqualTo(existingMember);
        assertThat(result.isNewMember()).isFalse();
        verify(memberRepository, never()).saveAndFlush(any(Member.class));
        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }

    //카카오 신규 회원 생성 실패 - 같은 이메일 계정이 있으면 기본 알림 설정 생성 안 함
    @Test
    void registerNewKakaoMemberDuplicateEmailNoNotificationSettingTest() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.empty());
        when(memberRepository.existsByEmail("dup@fitback.com")).thenReturn(true);

        assertThatThrownBy(() -> kakaoMemberRegistrationService.findOrRegister("dup@fitback.com", "12345"))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode())
                                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS.getCode()));

        verify(memberRepository, never()).saveAndFlush(any(Member.class));
        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }

    //카카오 신규 회원 생성 실패 - 재가입 차단이면 기본 알림 설정 생성 안 함
    @Test
    void registerNewKakaoMemberRejoinBlockedNoNotificationSettingTest() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.empty());
        when(memberRepository.existsByEmail("blocked@fitback.com")).thenReturn(false);
        when(rejoinBlockChecker.isRejoinBlocked("blocked@fitback.com")).thenReturn(true);

        assertThatThrownBy(() -> kakaoMemberRegistrationService.findOrRegister("blocked@fitback.com", "12345"))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode())
                                .isEqualTo(ErrorCode.REJOIN_BLOCKED.getCode()));

        verify(memberRepository, never()).saveAndFlush(any(Member.class));
        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }

    //카카오 신규 회원 동시 가입 - 소셜 UID 충돌 후 생성된 기존 회원 반환
    @Test
    void concurrentKakaoMemberSignupReturnsExistingMemberTest() {
        Member existingMember = Member.createSocial(
                "race@fitback.com",
                "nick",
                LoginProvider.KAKAO,
                "12345"
        );
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.empty(), Optional.of(existingMember));
        when(memberRepository.existsByEmail("race@fitback.com")).thenReturn(false);
        when(rejoinBlockChecker.isRejoinBlocked("race@fitback.com")).thenReturn(false);
        when(memberRepository.existsByNickname(anyString())).thenReturn(false);
        when(memberRepository.saveAndFlush(any(Member.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate kakao member"));

        KakaoMemberRegistrationService.KakaoMemberResult result =
                kakaoMemberRegistrationService.findOrRegister("race@fitback.com", "12345");

        assertThat(result.member()).isEqualTo(existingMember);
        assertThat(result.isNewMember()).isFalse();
        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }

    //카카오 신규 회원 동시 가입 실패 - 실제 이메일 중복인 경우에만 이메일 중복 오류 반환
    @Test
    void concurrentKakaoMemberSignupDuplicateEmailThrowsOAuthExceptionTest() {
        DataIntegrityViolationException duplicateException =
                new DataIntegrityViolationException("duplicate email");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(memberRepository.existsByEmail("race@fitback.com")).thenReturn(false, true);
        when(rejoinBlockChecker.isRejoinBlocked("race@fitback.com")).thenReturn(false);
        when(memberRepository.existsByNickname(anyString())).thenReturn(false);
        when(memberRepository.saveAndFlush(any(Member.class))).thenThrow(duplicateException);

        assertThatThrownBy(() -> kakaoMemberRegistrationService.findOrRegister(
                "race@fitback.com",
                "12345"
        ))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception -> {
                    assertThat(exception.getError().getErrorCode())
                            .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS.getCode());
                    assertThat(exception).hasCause(duplicateException);
                });

        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }

    //카카오 신규 회원 생성 실패 - 원인을 확인할 수 없는 DB 오류는 그대로 전달
    @Test
    void registerNewKakaoMemberUnexpectedConstraintRethrowsExceptionTest() {
        DataIntegrityViolationException unexpectedException =
                new DataIntegrityViolationException("unexpected constraint");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(memberRepository.findByLoginProviderAndSocialUid(LoginProvider.KAKAO, "12345"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(memberRepository.existsByEmail("race@fitback.com")).thenReturn(false, false);
        when(rejoinBlockChecker.isRejoinBlocked("race@fitback.com")).thenReturn(false);
        when(memberRepository.existsByNickname(anyString())).thenReturn(false);
        when(memberRepository.saveAndFlush(any(Member.class))).thenThrow(unexpectedException);

        assertThatThrownBy(() -> kakaoMemberRegistrationService.findOrRegister(
                "race@fitback.com",
                "12345"
        )).isSameAs(unexpectedException);

        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }
}
