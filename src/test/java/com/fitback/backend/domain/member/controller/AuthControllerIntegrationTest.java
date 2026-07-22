package com.fitback.backend.domain.member.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Transactional
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

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

    //이메일/비밀번호 요청 JSON 생성
    private String jsonBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    //회원가입 성공 테스트 - 200, 생성 코드, USER 권한, DB 저장
    @Test
    void signUpSuccessTest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("test@fitback.com", "password123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON201_1"))
                .andExpect(jsonPath("$.data.email").value("test@fitback.com"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        //DB에 회원 저장 확인
        assertThat(memberRepository.existsByEmail("test@fitback.com")).isTrue();
    }

    //회원가입 실패 테스트 - 이메일 중복 시 409
    @Test
    void signUpDuplicateEmailTest() throws Exception {
        signUp("dup@fitback.com", "password123");

        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("dup@fitback.com", "password123")))
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

    //로그인 성공 테스트 - 200, 토큰 발급
    @Test
    void loginSuccessTest() throws Exception {
        signUp("login@fitback.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("login@fitback.com", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.email").value("login@fitback.com"));
    }

    //로그인 실패 테스트 - 비밀번호 불일치 시 401
    @Test
    void loginWrongPasswordTest() throws Exception {
        signUp("login2@fitback.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("login2@fitback.com", "wrongPw")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_1"));
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

        //새 토큰이 DB에 저장되었는지 확인
        Member member = memberRepository.findByEmail("rotation@fitback.com").orElseThrow();
        assertThat(member.getRefreshToken()).isEqualTo(newRefreshToken);

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

        //DB refresh 토큰 null 초기화 확인
        Member member = memberRepository.findByEmail("logout@fitback.com").orElseThrow();
        assertThat(member.getRefreshToken()).isNull();
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
}
