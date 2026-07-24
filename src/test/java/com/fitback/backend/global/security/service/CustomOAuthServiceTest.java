package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.service.RejoinBlockChecker;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomOAuthServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RejoinBlockChecker rejoinBlockChecker;
    @Mock
    private NotificationSettingService notificationSettingService;

    @InjectMocks
    private CustomOAuthService customOAuthService;

    //카카오 신규 회원 생성 - 회원 저장 후 기본 알림 설정 생성
    @Test
    void registerNewKakaoMemberCreatesDefaultNotificationSettingTest() {
        when(memberRepository.existsByEmail("kakao@fitback.com")).thenReturn(false);
        when(rejoinBlockChecker.isRejoinBlocked("kakao@fitback.com")).thenReturn(false);
        when(memberRepository.existsByNickname(anyString())).thenReturn(false);
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> {
            Member member = inv.getArgument(0);
            ReflectionTestUtils.setField(member, "id", 1L);
            return member;
        });

        Member savedMember = ReflectionTestUtils.invokeMethod(
                customOAuthService,
                "registerNewKakaoMember",
                "Kakao@FITBACK.COM",
                "12345"
        );

        assertThat(savedMember.getEmail()).isEqualTo("kakao@fitback.com");
        assertThat(savedMember.getLoginProvider()).isEqualTo(LoginProvider.KAKAO);

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(memberCaptor.capture());
        verify(notificationSettingService).createDefaultSetting(savedMember);
        assertThat(memberCaptor.getValue().getSocialUid()).isEqualTo("12345");
    }

    //카카오 신규 회원 생성 실패 - 같은 이메일 계정이 있으면 기본 알림 설정 생성 안 함
    @Test
    void registerNewKakaoMemberDuplicateEmailNoNotificationSettingTest() {
        when(memberRepository.existsByEmail("dup@fitback.com")).thenReturn(true);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                customOAuthService,
                "registerNewKakaoMember",
                "dup@fitback.com",
                "12345"
        )).isInstanceOf(OAuth2AuthenticationException.class);

        verify(memberRepository, never()).save(any(Member.class));
        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }

    //카카오 신규 회원 생성 실패 - 재가입 차단이면 기본 알림 설정 생성 안 함
    @Test
    void registerNewKakaoMemberRejoinBlockedNoNotificationSettingTest() {
        when(memberRepository.existsByEmail("blocked@fitback.com")).thenReturn(false);
        when(rejoinBlockChecker.isRejoinBlocked("blocked@fitback.com")).thenReturn(true);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                customOAuthService,
                "registerNewKakaoMember",
                "blocked@fitback.com",
                "12345"
        )).isInstanceOf(OAuth2AuthenticationException.class);

        verify(memberRepository, never()).save(any(Member.class));
        verify(notificationSettingService, never()).createDefaultSetting(any(Member.class));
    }
}
