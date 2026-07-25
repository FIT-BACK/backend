package com.fitback.backend.domain.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Transactional
class MemberControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TagRepository tagRepository;

    //JSON 생성/파싱용, 컨텍스트에 빈이 없어 직접 생성
    private final ObjectMapper objectMapper = new ObjectMapper();

    //회원가입 후 응답 data 노드에서 특정 토큰 값 반환
    private String signUpAndGetToken(String email, String password, String tokenField) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        String responseBody = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("data").get(tokenField).asText();
    }

    //회원가입 후 access 토큰 반환 (member API는 전부 인증 필요)
    private String signUpAndGetAccessToken(String email, String password) throws Exception {
        return signUpAndGetToken(email, password, "accessToken");
    }

    //Bearer 헤더 값 생성
    private String bearer(String token) {
        return "Bearer " + token;
    }

    //테스트용 태그 저장 후 id 반환 (관심 태그 API 픽스처)
    private Long saveTag(String name, TagType type) {
        return tagRepository.save(Tag.create(name, type)).getId();
    }

    //JSON 문자열 생성
    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ---------- 인증 가드 ----------

    //토큰 없이 마이페이지 조회 시 401
    @Test
    void myPageWithoutTokenTest() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 회원정보 수정 시 401
    @Test
    void updateMemberWithoutTokenTest() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "someNick"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 비밀번호 변경 시 401
    @Test
    void changePasswordWithoutTokenTest() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "password123", "newPassword", "newPassword456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 회원 탈퇴 시 401
    @Test
    void deleteAccountWithoutTokenTest() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 온보딩 시 401
    @Test
    void onboardingWithoutTokenTest() throws Exception {
        mockMvc.perform(put("/api/v1/members/me/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "someNick", "tagIds", List.of()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 관심 태그 수정 시 401
    @Test
    void updateTagsWithoutTokenTest() throws Exception {
        mockMvc.perform(put("/api/v1/members/me/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tagIds", List.of()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //refresh 토큰을 Bearer로 보내면 401 (access 타입 아님)
    @Test
    void refreshTokenAsBearerRejectedTest() throws Exception {
        String refreshToken = signUpAndGetToken("bearer@fitback.com", "password123", "refreshToken");

        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", bearer(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //토큰 없이 닉네임 사용 가능 여부 확인 시 401
    @Test
    void checkNicknameAvailabilityWithoutTokenTest() throws Exception {
        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .queryParam("nickname", "newNick"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    // ---------- nickname availability ----------

    //닉네임 사용 가능 여부 확인 - 사용 중인 회원이 없으면 true
    @Test
    void checkNicknameAvailabilityAvailableTest() throws Exception {
        String token = signUpAndGetAccessToken("nickcheck@fitback.com", "password123");

        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .header("Authorization", bearer(token))
                        .queryParam("nickname", "newNick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.nickname").value("newNick"))
                .andExpect(jsonPath("$.data.available").value(true));
    }

    //닉네임 사용 가능 여부 확인 - 다른 회원이 사용 중이면 false
    @Test
    void checkNicknameAvailabilityDuplicateTest() throws Exception {
        String tokenA = signUpAndGetAccessToken("nickdupA@fitback.com", "password123");
        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "takenNick"))))
                .andExpect(status().isOk());

        String tokenB = signUpAndGetAccessToken("nickdupB@fitback.com", "password123");

        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .header("Authorization", bearer(tokenB))
                        .queryParam("nickname", "takenNick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("takenNick"))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    //닉네임 사용 가능 여부 확인 - 현재 내 닉네임은 true
    @Test
    void checkNicknameAvailabilityOwnNicknameTest() throws Exception {
        String token = signUpAndGetAccessToken("nickown@fitback.com", "password123");
        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "ownNick"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .header("Authorization", bearer(token))
                        .queryParam("nickname", "ownNick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("ownNick"))
                .andExpect(jsonPath("$.data.available").value(true));
    }

    //닉네임 사용 가능 여부 확인 - nickname 누락 시 400
    @Test
    void checkNicknameAvailabilityMissingNicknameTest() throws Exception {
        String token = signUpAndGetAccessToken("nickmissing@fitback.com", "password123");

        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //닉네임 사용 가능 여부 확인 - 공백 닉네임이면 400
    @Test
    void checkNicknameAvailabilityBlankNicknameTest() throws Exception {
        String token = signUpAndGetAccessToken("nickblank@fitback.com", "password123");

        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .header("Authorization", bearer(token))
                        .queryParam("nickname", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //닉네임 사용 가능 여부 확인 - 1자 닉네임이면 400
    @Test
    void checkNicknameAvailabilityTooShortTest() throws Exception {
        String token = signUpAndGetAccessToken("nickshort@fitback.com", "password123");

        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .header("Authorization", bearer(token))
                        .queryParam("nickname", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //닉네임 사용 가능 여부 확인 - 17자 닉네임이면 400
    @Test
    void checkNicknameAvailabilityTooLongTest() throws Exception {
        String token = signUpAndGetAccessToken("nicklong@fitback.com", "password123");

        mockMvc.perform(get("/api/v1/members/me/nickname-availability")
                        .header("Authorization", bearer(token))
                        .queryParam("nickname", "n".repeat(17)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    // ---------- myPage ----------

    //마이페이지 성공 - 명세 필드 전부 반환, 신규 회원은 count 0·태그 빈 배열
    @Test
    void myPageSuccessTest() throws Exception {
        String token = signUpAndGetAccessToken("mypage@fitback.com", "password123");

        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.memberId").isNumber())
                .andExpect(jsonPath("$.data.email").value("mypage@fitback.com"))
                .andExpect(jsonPath("$.data.nickname").exists())
                .andExpect(jsonPath("$.data.loginProvider").value("EMAIL"))
                .andExpect(jsonPath("$.data.savedCount").value(0))
                .andExpect(jsonPath("$.data.analysisCount").value(0))
                .andExpect(jsonPath("$.data.uploadCount").value(0))
                .andExpect(jsonPath("$.data.tags").isEmpty());
    }

    // ---------- updateMember ----------

    //회원정보 수정 - 닉네임만 보내면 프로필·태그는 기존 값 유지 (부분 수정)
    @Test
    void updateMemberPartialKeepsOthersTest() throws Exception {
        String token = signUpAndGetAccessToken("partial@fitback.com", "password123");
        Long tagId1 = saveTag("미니멀", TagType.SILHOUETTE);
        Long tagId2 = saveTag("블랙", TagType.COLOR);

        //먼저 프로필 이미지와 태그 2개 세팅
        mockMvc.perform(put("/api/v1/members/me/onboarding")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "nickname", "beforeNick",
                                "profileImageUrl", "http://img/keep.png",
                                "tagIds", List.of(tagId1, tagId2)))))
                .andExpect(status().isOk());

        //닉네임만 수정, profileImageUrl·tagIds는 미전송
        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "afterNick"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("afterNick"))
                //미전송 필드는 기존 값 유지
                .andExpect(jsonPath("$.data.profileImageUrl").value("http://img/keep.png"))
                .andExpect(jsonPath("$.data.tags.length()").value(2));
    }

    //회원정보 수정 - 다른 회원이 쓰는 닉네임이면 409
    @Test
    void updateMemberDuplicateNicknameTest() throws Exception {
        //회원 A가 먼저 닉네임 선점
        String tokenA = signUpAndGetAccessToken("dupA@fitback.com", "password123");
        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "takenNick"))))
                .andExpect(status().isOk());

        //회원 B가 동일 닉네임 시도
        String tokenB = signUpAndGetAccessToken("dupB@fitback.com", "password123");
        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "takenNick"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER409_1"));
    }

    //회원정보 수정 - 닉네임 1자면 검증 오류 400
    @Test
    void updateMemberNicknameTooShortTest() throws Exception {
        String token = signUpAndGetAccessToken("short@fitback.com", "password123");

        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "a"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //회원정보 수정 - 닉네임 17자면 검증 오류 400
    @Test
    void updateMemberNicknameTooLongTest() throws Exception {
        String token = signUpAndGetAccessToken("long@fitback.com", "password123");

        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "n".repeat(17)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //회원정보 수정 - tagIds 6개면 최대 개수 초과 검증 오류 400
    @Test
    void updateMemberTooManyTagsTest() throws Exception {
        String token = signUpAndGetAccessToken("manytags@fitback.com", "password123");

        //검증에서 먼저 걸리므로 존재하지 않는 id여도 무관
        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tagIds", List.of(1, 2, 3, 4, 5, 6)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    // ---------- changePassword ----------

    //비밀번호 변경 성공 - 기존 비밀번호 로그인 실패, 새 비밀번호 로그인 성공
    @Test
    void changePasswordSuccessTest() throws Exception {
        String token = signUpAndGetAccessToken("changepw@fitback.com", "password123");

        mockMvc.perform(patch("/api/v1/members/me/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "password123", "newPassword", "newPassword456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"));

        //기존 비밀번호 로그인은 실패
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "changepw@fitback.com", "password", "password123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH401_1"));

        //새 비밀번호 로그인은 성공
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "changepw@fitback.com", "password", "newPassword456"))))
                .andExpect(status().isOk());
    }

    //비밀번호 변경 - 현재 비밀번호 불일치 시 400
    @Test
    void changePasswordMismatchTest() throws Exception {
        String token = signUpAndGetAccessToken("changepw2@fitback.com", "password123");

        mockMvc.perform(patch("/api/v1/members/me/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "wrongPw", "newPassword", "newPassword456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER400_1"));
    }

    //비밀번호 변경 - 새 비밀번호가 공백이면 검증 오류 400
    @Test
    void changePasswordBlankTest() throws Exception {
        String token = signUpAndGetAccessToken("blankpw@fitback.com", "password123");

        mockMvc.perform(patch("/api/v1/members/me/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "password123", "newPassword", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    //비밀번호 변경 - 새 비밀번호가 8자 미만이면 검증 오류 400
    @Test
    void changePasswordTooShortTest() throws Exception {
        String token = signUpAndGetAccessToken("shortpw@fitback.com", "password123");

        mockMvc.perform(patch("/api/v1/members/me/password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "password123", "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    // ---------- onboarding ----------

    //온보딩 성공 - 프로필과 태그 저장
    @Test
    void onboardingSuccessTest() throws Exception {
        String token = signUpAndGetAccessToken("onboarding@fitback.com", "password123");
        Long tagId1 = saveTag("미니멀", TagType.SILHOUETTE);
        Long tagId2 = saveTag("블랙", TagType.COLOR);

        mockMvc.perform(put("/api/v1/members/me/onboarding")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "nickname", "onboardNick",
                                "profileImageUrl", "http://img/p.png",
                                "tagIds", List.of(tagId1, tagId2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.nickname").value("onboardNick"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("http://img/p.png"))
                .andExpect(jsonPath("$.data.tags.length()").value(2));
    }

    //온보딩 - 존재하지 않는 태그 포함 시 400
    @Test
    void onboardingInvalidTagTest() throws Exception {
        String token = signUpAndGetAccessToken("onboarding2@fitback.com", "password123");

        mockMvc.perform(put("/api/v1/members/me/onboarding")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "nickname", "onboardNick",
                                "tagIds", List.of(999999)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER400_3"));
    }

    //온보딩 - tagIds 필드 누락 시 검증 오류 400
    @Test
    void onboardingMissingTagIdsTest() throws Exception {
        String token = signUpAndGetAccessToken("onboarding3@fitback.com", "password123");

        //tagIds는 @NotNull (빈 배열은 허용하나 필드 자체는 필수)
        mockMvc.perform(put("/api/v1/members/me/onboarding")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "onboardNick"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    // ---------- updateTags ----------

    //관심 태그 수정 - 기존 태그를 새 태그로 완전 교체
    @Test
    void updateTagsReplaceTest() throws Exception {
        String token = signUpAndGetAccessToken("tags@fitback.com", "password123");
        Long tagId1 = saveTag("미니멀", TagType.SILHOUETTE);
        Long tagId2 = saveTag("블랙", TagType.COLOR);
        Long tagId3 = saveTag("디테일", TagType.DETAIL);

        //초기 태그 2개 세팅
        mockMvc.perform(put("/api/v1/members/me/tags")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tagIds", List.of(tagId1, tagId2)))))
                .andExpect(status().isOk());

        //태그 3만 남기고 교체
        mockMvc.perform(put("/api/v1/members/me/tags")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tagIds", List.of(tagId3)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.tags.length()").value(1))
                .andExpect(jsonPath("$.data.tags[0].tagId").value(tagId3));
    }

    //관심 태그 수정 - 존재하지 않는 태그 포함 시 400
    @Test
    void updateTagsInvalidTagTest() throws Exception {
        String token = signUpAndGetAccessToken("tags2@fitback.com", "password123");

        mockMvc.perform(put("/api/v1/members/me/tags")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tagIds", List.of(999999)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER400_3"));
    }

    // ---------- deleteAccount ----------

    //회원 탈퇴 - 삭제 후 동일 이메일 재가입 시 30일 차단 403
    @Test
    void deleteAccountBlocksRejoinTest() throws Exception {
        String token = signUpAndGetAccessToken("delete@fitback.com", "password123");

        mockMvc.perform(delete("/api/v1/members/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"));

        //회원 hard delete 확인
        assertThat(memberRepository.existsByEmail("delete@fitback.com")).isFalse();

        //동일 이메일로 재가입 시도하면 30일 재가입 차단
        mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "delete@fitback.com", "password", "password123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER403_1"));
    }
}
