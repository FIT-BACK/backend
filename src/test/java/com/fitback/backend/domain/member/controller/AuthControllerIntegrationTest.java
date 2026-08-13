package com.fitback.backend.domain.member.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.repository.PasswordResetTokenRepository;
import com.fitback.backend.domain.member.service.LoginAttemptService;
import com.fitback.backend.domain.member.service.PasswordResetMailSender;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.repository.MemberNotificationSettingRepository;
import com.fitback.backend.global.security.token.TempTokenPayload;
import com.fitback.backend.global.security.token.TempTokenStore;
import com.fitback.backend.global.util.HmacUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Transactional
class AuthControllerIntegrationTest {

    private static final String REFRESH_TOKEN_HASH_CONTEXT = "refresh-token:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberNotificationSettingRepository notificationSettingRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TempTokenStore tempTokenStore;

    @Autowired
    private HmacUtil hmacUtil;

    @MockitoBean
    private PasswordResetMailSender passwordResetMailSender;

    private String loginAttemptEmail;

    // 테스트 트랜잭션 롤백 후 REQUIRES_NEW로 커밋된 로그인 실패 기록 제거
    @AfterTransaction
    void clearLoginAttempts() {
        if (loginAttemptEmail != null) {
            loginAttemptService.clear(loginAttemptEmail);
        }
    }

    //JSON 생성/파싱용, 컨텍스트에 빈이 없어 직접 생성
    private final ObjectMapper objectMapper = new ObjectMapper();

    //회원가입 요청 후 응답 data 노드 반환 (토큰 추출용)
    private JsonNode signUp(String email, String password) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        String responseBody = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("data");
    }

    private String hashRefreshToken(String refreshToken) {
        return hmacUtil.hashHex(REFRESH_TOKEN_HASH_CONTEXT + refreshToken);
    }

    //이메일/비밀번호 요청 JSON 생성
    private String jsonBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    //회원가입 성공 테스트 - 200, 생성 코드, USER 권한, DB 저장
    @Test
    void signUpSuccessTest() throws Exception {
        String responseBody = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("Test@FITBACK.COM", "password123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON201_1"))
                .andExpect(jsonPath("$.data.email").value("test@fitback.com"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andReturn().getResponse().getContentAsString();

        //DB에 회원 저장 확인
        assertThat(memberRepository.existsByEmail("test@fitback.com")).isTrue();

        //회원가입 시 기본 알림 설정 저장 확인
        Member member = memberRepository.findByEmail("test@fitback.com").orElseThrow();
        String refreshToken = objectMapper.readTree(responseBody).get("data").get("refreshToken").asText();
        assertThat(member.getRefreshTokenHash()).isEqualTo(hashRefreshToken(refreshToken));
        MemberNotificationSetting setting = notificationSettingRepository.findById(member.getId()).orElseThrow();
        assertThat(setting.getAnalysisCompleteEnabled()).isTrue();
        assertThat(setting.getLookbookLikedEnabled()).isTrue();
        assertThat(setting.getTrendUpdateEnabled()).isFalse();
        assertThat(setting.getMarketingEnabled()).isFalse();
    }

    //회원가입 실패 테스트 - 이메일 중복 시 409
    @Test
    void signUpDuplicateEmailTest() throws Exception {
        signUp("dup@fitback.com", "password123");

        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("Dup@FITBACK.COM", "password123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH409_1"));
    }

    //회원가입 실패 테스트 - 이메일 형식 오류 시 400
    @Test
    void signUpInvalidEmailTest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("not-an-email", "password123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @Test
    void signUpRejectsPasswordOverBcryptByteLimit() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("byte-limit@fitback.com", "가".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));

        assertThat(memberRepository.existsByEmail("byte-limit@fitback.com")).isFalse();
    }

    @Test
    void signUpRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("short-password@fitback.com", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));

        assertThat(memberRepository.existsByEmail("short-password@fitback.com")).isFalse();
    }

    //로그인 성공 테스트 - 200, 토큰 발급
    @Test
    void loginSuccessTest() throws Exception {
        signUp("login@fitback.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("Login@FITBACK.COM", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.email").value("login@fitback.com"));
    }

    //로그인 실패 테스트 - 비밀번호 불일치 시 401
    @Test
    void loginWrongPasswordTest() throws Exception {
        loginAttemptEmail = "login2@fitback.com";
        signUp(loginAttemptEmail, "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(loginAttemptEmail, "wrongPw")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_1"));
    }

    // 동일 이메일의 1~4회 실패는 401, 다섯 번째 실패부터 429 잠금 응답
    @Test
    void loginFifthFailureLocksEmailTest() throws Exception {
        loginAttemptEmail = "login-lock@fitback.com";
        signUp(loginAttemptEmail, "password123");
        String wrongCredentials = jsonBody("Login-Lock@FITBACK.COM", "wrongPw");

        for (int attempt = 1; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(wrongCredentials))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH401_1"));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongCredentials))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH429_1"));

        // 잠금 중에는 올바른 비밀번호를 보내도 잠금 만료 전까지 동일하게 차단
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("login-lock@fitback.com", "password123")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH429_1"));
    }

    //비밀번호 재설정 링크 요청 성공 - 인증 없이 토큰 저장 및 메일 발송
    @Test
    void passwordResetLinkRequestSuccessTest() throws Exception {
        signUp("reset-link@fitback.com", "password123");

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "Reset-Link@FITBACK.COM")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200_1"));

        Member member = memberRepository.findByEmail("reset-link@fitback.com")
                .orElseThrow();
        assertThat(passwordResetTokenRepository.findById(member.getId())).isPresent();
        verify(passwordResetMailSender)
                .sendResetLink(eq("reset-link@fitback.com"), anyString());
    }

    //비밀번호 재설정 링크 요청 - 미가입 이메일도 동일 성공 응답
    @Test
    void passwordResetLinkRequestUnknownEmailSuccessTest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "unknown@fitback.com")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200_1"));

        verify(passwordResetMailSender, never())
                .sendResetLink(anyString(), anyString());
    }

    //비밀번호 재설정 링크 요청 - 소셜 회원도 동일 성공 응답
    @Test
    void passwordResetLinkRequestSocialMemberSuccessTest() throws Exception {
        Member socialMember = memberRepository.save(Member.createSocial(
                "social-reset@fitback.com",
                "social_reset_member",
                LoginProvider.KAKAO,
                "social-reset-uid"
        ));

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "social-reset@fitback.com")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200_1"));

        assertThat(passwordResetTokenRepository.findById(socialMember.getId()))
                .isEmpty();
        verify(passwordResetMailSender, never())
                .sendResetLink(anyString(), anyString());
    }

    //비밀번호 재설정 링크 요청 실패 - 이메일 형식 오류
    @Test
    void passwordResetLinkRequestInvalidEmailTest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "invalid-email")
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //비밀번호 재설정 성공 - 비밀번호와 refresh token 변경 후 토큰 삭제
    @Test
    void passwordResetSuccessTest() throws Exception {
        signUp("reset-password@fitback.com", "password123");

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "reset-password@fitback.com")
                        )))
                .andExpect(status().isOk());

        ArgumentCaptor<String> resetTokenCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(passwordResetMailSender).sendResetLink(
                eq("reset-password@fitback.com"),
                resetTokenCaptor.capture()
        );

        mockMvc.perform(patch("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "resetToken", resetTokenCaptor.getValue(),
                                "newPassword", "newPassword123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200_1"));

        Member member = memberRepository.findByEmail("reset-password@fitback.com")
                .orElseThrow();
        assertThat(passwordEncoder.matches(
                "newPassword123",
                member.getPassword()
        )).isTrue();
        assertThat(member.getRefreshTokenHash()).isNull();
        assertThat(passwordResetTokenRepository.findById(member.getId())).isEmpty();
    }

    //비밀번호 재설정 실패 - 유효하지 않은 토큰
    @Test
    void passwordResetInvalidTokenTest() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "resetToken", "invalid-reset-token",
                                "newPassword", "newPassword123"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_4"));
    }

    //비밀번호 재설정 실패 - 빈 토큰과 짧은 비밀번호
    @Test
    void passwordResetValidationTest() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "resetToken", "",
                                "newPassword", "short"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //토큰 재발급 성공 테스트 - 200, 새 토큰
    @Test
    void refreshSuccessTest() throws Exception {
        JsonNode data = signUp("refresh@fitback.com", "password123");
        String refreshToken = data.get("refreshToken").asText();

        String requestBody = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists());
    }

    //토큰 재발급 실패 테스트 - 유효하지 않은 토큰 시 401
    @Test
    void refreshInvalidTokenTest() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of("refreshToken", "invalid-token"));

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_2"));
    }

    //토큰 재발급 회전 테스트 - 새 토큰 DB 저장, 기존 토큰 재사용 차단
    @Test
    void refreshRotationBlocksOldTokenTest() throws Exception {
        JsonNode data = signUp("rotation@fitback.com", "password123");
        String oldRefreshToken = data.get("refreshToken").asText();

        //기존 토큰으로 재발급 후 새 refresh 토큰 추출
        String responseBody = mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefreshToken = objectMapper.readTree(responseBody).get("data").get("refreshToken").asText();

        //회전으로 새 토큰은 기존 토큰과 달라야 함
        assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken);

        //새 토큰 원문이 아닌 HMAC 해시가 DB에 저장되었는지 확인
        Member member = memberRepository.findByEmail("rotation@fitback.com").orElseThrow();
        assertThat(member.getRefreshTokenHash()).isEqualTo(hashRefreshToken(newRefreshToken));

        //기존 토큰으로 다시 재발급 시 401 (재사용 차단)
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_2"));
    }

    //로그아웃 성공 테스트 - access 토큰으로 200, DB refresh 토큰 초기화
    @Test
    void logoutSuccessTest() throws Exception {
        JsonNode data = signUp("logout@fitback.com", "password123");
        String accessToken = data.get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"));

        //DB refresh 토큰 해시 null 초기화 확인
        Member member = memberRepository.findByEmail("logout@fitback.com").orElseThrow();
        assertThat(member.getRefreshTokenHash()).isNull();
    }

    //로그아웃 실패 테스트 - 토큰 없으면 401
    @Test
    void logoutWithoutTokenTest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //로그아웃 실패 테스트 - refresh 토큰을 Bearer로 보내면 401 (access 타입 아님)
    @Test
    void logoutWithRefreshTokenTest() throws Exception {
        JsonNode data = signUp("logout2@fitback.com", "password123");
        String refreshToken = data.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //임시 토큰 교환 성공 테스트 - 인증 헤더 없이 200(permitAll), 새 refresh 토큰이 DB에 저장
    @Test
    void exchangeTokenSuccessTest() throws Exception {
        //교환 대상 회원 생성 후 임시 토큰 발급
        JsonNode data = signUp("exchange@fitback.com", "password123");
        long memberId = data.get("memberId").asLong();
        String tempToken = tempTokenStore.issue(new TempTokenPayload(memberId, true));

        //인증 헤더 없이 교환 (permitAll)
        String responseBody = mockMvc.perform(post("/api/v1/auth/token/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("tempToken", tempToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andExpect(jsonPath("$.data.isNewMember").value(true))
                .andReturn().getResponse().getContentAsString();

        //교환으로 발급된 refresh 토큰 원문이 아닌 HMAC 해시 저장 검증
        String newRefreshToken = objectMapper.readTree(responseBody).get("data").get("refreshToken").asText();
        Member member = memberRepository.findByEmail("exchange@fitback.com").orElseThrow();
        assertThat(member.getRefreshTokenHash()).isEqualTo(hashRefreshToken(newRefreshToken));
    }

    //임시 토큰 교환 실패 테스트 - 유효하지 않은 임시 토큰은 401 + AUTH401_3 (보안이 아닌 비즈니스 계층 도달 = permitAll 확인)
    @Test
    void exchangeInvalidTokenTest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("tempToken", "invalid-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_3"));
    }

    //임시 토큰 교환 실패 테스트 - 빈 임시 토큰은 검증 오류 400
    @Test
    void exchangeEmptyTokenTest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("tempToken", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //임시 토큰은 일회용 - 한 번 교환한 토큰은 재교환 시 401
    @Test
    void exchangeReusedTokenTest() throws Exception {
        JsonNode data = signUp("reuse@fitback.com", "password123");
        long memberId = data.get("memberId").asLong();
        String tempToken = tempTokenStore.issue(new TempTokenPayload(memberId, false));

        //1회차 교환 성공
        mockMvc.perform(post("/api/v1/auth/token/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("tempToken", tempToken))))
                .andExpect(status().isOk());

        //2회차는 이미 소비되어 401
        mockMvc.perform(post("/api/v1/auth/token/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("tempToken", tempToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_3"));
    }

    //보안 설정 - 카카오 로그인 시작 URL은 인증 없이 카카오 인가 페이지로 리다이렉트
    @Test
    void kakaoAuthorizationRedirectsWithoutAuthTest() throws Exception {
        String location = mockMvc.perform(get("/api/v1/auth/oauth2/kakao"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize");
    }
}
