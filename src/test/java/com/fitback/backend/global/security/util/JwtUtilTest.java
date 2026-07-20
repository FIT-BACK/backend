package com.fitback.backend.global.security.util;


import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.security.entity.AuthMember;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    //HS256 서명용 최소 32바이트 키 필요, 넉넉히 긴 시크릿 사용
    private static final String SECRET = "test-secret-key-for-jwt-unit-test-1234567890";
    private static final long ACCESS_MS = 1800000L;       //access 토큰 만료 30분
    private static final long REFRESH_MS = 1209600000L;  //refresh 토큰 만료 14일

    //테스트 대상 JwtUtil (시크릿, 만료시간 직접 주입)
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, ACCESS_MS, REFRESH_MS);

    //토큰 발급용 AuthMember 생성
    private AuthMember createTestAuthMember(){
        Member member = Member.create("test@fitback.com", "test_user", "encodedPw", LoginProvider.EMAIL);
        return new AuthMember(member);
    }

    //access 토큰 발급 테스트 - 유효, access 타입, 이메일 포함
    @Test
    void accessTokenValidTest(){
        String token = jwtUtil.createAccessToken(createTestAuthMember());

        assertThat(jwtUtil.isValid(token)).isTrue();
        assertThat(jwtUtil.isAccessToken(token)).isTrue();
        //access 토큰이므로 refresh 타입 아님
        assertThat(jwtUtil.isRefreshToken(token)).isFalse();
        //토큰 subject에 이메일 포함
        assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("test@fitback.com");
    }

    //refresh 토큰 발급 테스트 - 유효, refresh 타입
    @Test
    void refreshTokenValidTest(){
        String token = jwtUtil.createRefreshToken(createTestAuthMember());

        assertThat(jwtUtil.isValid(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isTrue();
        //refresh 토큰이므로 access 타입 아님
        assertThat(jwtUtil.isAccessToken(token)).isFalse();
    }

    //만료 토큰 테스트 - 만료 토큰은 유효하지 않음
    @Test
    void expiredTokenInvalidTest(){
        //검증에 60초 여유(clockSkew) 있어 -61초로 만료시켜 이미 만료된 토큰 발급
        JwtUtil expiredJwtUtil = new JwtUtil(SECRET, -61000L, -61000L);
        String expiredToken = expiredJwtUtil.createAccessToken(createTestAuthMember());

        assertThat(expiredJwtUtil.isValid(expiredToken)).isFalse();
        //만료 토큰에서 이메일 추출 시 null 반환
        assertThat(expiredJwtUtil.getEmailFromToken(expiredToken)).isNull();
    }

    //위조 토큰 테스트 - 다른 시크릿 서명 토큰은 유효하지 않음
    @Test
    void forgedTokenInvalidTest(){
        //공격자가 다른 시크릿으로 만든 토큰
        JwtUtil attackerJwtUtil = new JwtUtil("another-different-secret-key-for-attacker-0987654321", ACCESS_MS, REFRESH_MS);
        String forgedToken = attackerJwtUtil.createAccessToken(createTestAuthMember());

        //우리 시크릿으로 검증 시 서명 불일치로 실패
        assertThat(jwtUtil.isValid(forgedToken)).isFalse();
    }

    //형식 오류 토큰 테스트 - 깨진 문자열은 유효하지 않고 이메일도 null
    @Test
    void malformedTokenInvalidTest(){
        String malformedToken = "not.a.jwt";

        assertThat(jwtUtil.isValid(malformedToken)).isFalse();
        assertThat(jwtUtil.getEmailFromToken(malformedToken)).isNull();
    }
}
