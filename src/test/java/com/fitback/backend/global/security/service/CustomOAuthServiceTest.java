package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.OAuthMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomOAuthServiceTest {

    @Mock
    private KakaoMemberRegistrationService kakaoMemberRegistrationService;

    @Mock
    private OAuth2UserRequest userRequest;

    //카카오 사용자 정보를 네트워크 호출 없이 주입하기 위한 테스트용 서비스
    private static class TestCustomOAuthService extends CustomOAuthService {

        private final OAuth2User oAuth2User;

        private TestCustomOAuthService(
                KakaoMemberRegistrationService kakaoMemberRegistrationService,
                OAuth2User oAuth2User
        ) {
            super(kakaoMemberRegistrationService);
            this.oAuth2User = oAuth2User;
        }

        @Override
        protected OAuth2User loadOAuthUser(OAuth2UserRequest userRequest) {
            return oAuth2User;
        }
    }

    //카카오 사용자 정보 조회 후 socialUid와 정규화된 email로 회원 조회/가입 서비스 호출
    @Test
    void loadUserDelegatesKakaoRegistrationTest() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("id")).thenReturn(12345L);
        when(oAuth2User.getAttribute("kakao_account")).thenReturn(Map.of("email", "Kakao@FITBACK.COM"));
        when(oAuth2User.getAttributes()).thenReturn(Map.of("id", 12345L));

        Member member = Member.createSocial("kakao@fitback.com", "nick", LoginProvider.KAKAO, "12345");
        ReflectionTestUtils.setField(member, "id", 1L);
        when(kakaoMemberRegistrationService.findOrRegister("kakao@fitback.com", "12345"))
                .thenReturn(new KakaoMemberRegistrationService.KakaoMemberResult(member, true));

        CustomOAuthService customOAuthService =
                new TestCustomOAuthService(kakaoMemberRegistrationService, oAuth2User);

        OAuthMember result = (OAuthMember) customOAuthService.loadUser(userRequest);

        assertThat(result.getMember()).isEqualTo(member);
        assertThat(result.isNewMember()).isTrue();
        verify(kakaoMemberRegistrationService).findOrRegister("kakao@fitback.com", "12345");
    }

    //카카오 id가 없으면 "null" socialUid를 만들지 않고 OAuth 실패로 처리
    @Test
    void loadUserWithoutKakaoIdThrowsOAuthExceptionTest() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("id")).thenReturn(null);

        CustomOAuthService customOAuthService =
                new TestCustomOAuthService(kakaoMemberRegistrationService, oAuth2User);

        assertThatThrownBy(() -> customOAuthService.loadUser(userRequest))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode())
                                .isEqualTo(ErrorCode.SOCIAL_ID_REQUIRED.getCode()));
    }

    //카카오 계정 정보가 없으면 이메일 필수 오류로 OAuth 실패 처리
    @Test
    void loadUserWithoutKakaoAccountThrowsOAuthExceptionTest() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("id")).thenReturn(12345L);
        when(oAuth2User.getAttribute("kakao_account")).thenReturn(null);

        CustomOAuthService customOAuthService =
                new TestCustomOAuthService(kakaoMemberRegistrationService, oAuth2User);

        assertThatThrownBy(() -> customOAuthService.loadUser(userRequest))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode())
                                .isEqualTo(ErrorCode.SOCIAL_EMAIL_REQUIRED.getCode()));

        verify(kakaoMemberRegistrationService, never()).findOrRegister(anyString(), anyString());
    }

    //카카오 계정에 이메일이 없으면 이메일 필수 오류로 OAuth 실패 처리
    @Test
    void loadUserWithoutKakaoEmailThrowsOAuthExceptionTest() {
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("id")).thenReturn(12345L);
        when(oAuth2User.getAttribute("kakao_account")).thenReturn(Map.of());

        CustomOAuthService customOAuthService =
                new TestCustomOAuthService(kakaoMemberRegistrationService, oAuth2User);

        assertThatThrownBy(() -> customOAuthService.loadUser(userRequest))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, exception ->
                        assertThat(exception.getError().getErrorCode())
                                .isEqualTo(ErrorCode.SOCIAL_EMAIL_REQUIRED.getCode()));

        verify(kakaoMemberRegistrationService, never()).findOrRegister(anyString(), anyString());
    }
}
