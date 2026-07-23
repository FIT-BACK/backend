package com.fitback.backend.global.security.handler;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.security.entity.OAuthMember;
import com.fitback.backend.global.security.token.TempTokenPayload;
import com.fitback.backend.global.security.token.TempTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthSuccessHandlerTest {

    private static final String FRONT_REDIRECT_URI = "http://localhost:3000/oauth/success";

    @Mock
    private TempTokenStore tempTokenStore;

    @InjectMocks
    private OAuthSuccessHandler handler;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        //@Value 주입 필드 수동 세팅
        ReflectionTestUtils.setField(handler, "frontRedirectUri", FRONT_REDIRECT_URI);
    }

    //id를 강제 세팅한 테스트 회원 생성
    private Member createTestMember(Long id) {
        Member member = Member.createSocial("kakao@fitback.com", "nick", LoginProvider.KAKAO, "kakao-uid");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    //인증 성공 시 실제 JWT가 아닌 임시 토큰만 발급하고, 그 토큰을 붙여 프론트로 리다이렉트
    @Test
    void issuesTempTokenAndRedirectsTest() throws Exception {
        Member member = createTestMember(1L);
        //신규 가입자 principal
        OAuthMember principal = new OAuthMember(member, Map.of(), true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);

        when(tempTokenStore.issue(any(TempTokenPayload.class))).thenReturn("temp-abc");

        handler.onAuthenticationSuccess(request, response, authentication);

        //임시 토큰에 memberId·isNewMember가 정확히 실렸는지 검증
        ArgumentCaptor<TempTokenPayload> payloadCaptor = ArgumentCaptor.forClass(TempTokenPayload.class);
        verify(tempTokenStore).issue(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().memberId()).isEqualTo(1L);
        assertThat(payloadCaptor.getValue().isNewMember()).isTrue();

        //redirect URL이 FRONT?tempToken=... 형태이고 실제 토큰은 노출되지 않음
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(urlCaptor.capture());
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).isEqualTo(FRONT_REDIRECT_URI + "?tempToken=temp-abc");
        assertThat(redirectUrl).doesNotContain("accessToken");
        assertThat(redirectUrl).doesNotContain("refreshToken");
    }
}
