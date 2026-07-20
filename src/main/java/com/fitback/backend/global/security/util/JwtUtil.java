package com.fitback.backend.global.security.util;

import com.fitback.backend.global.security.entity.AuthMember;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;


//JWT 토큰 발급
@Component
public class JwtUtil {

    // 토큰 종류 구분용 claim
    private static final String CLAIM_TYPE = "type";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final SecretKey secretKey;
    //accesstoken 만료 시간
    private final Duration accessExpiration;
    //refreshtoken 만료 시간
    private final Duration refreshExpiration;

    public JwtUtil(
            @Value("${jwt.token.secretKey}") String configSecretKey,
            @Value("${jwt.token.expiration.access}") Long accessExpiration,
            @Value("${jwt.token.expiration.refresh}") Long refreshExpiration
    ){
        this.secretKey = Keys.hmacShaKeyFor(configSecretKey.getBytes(StandardCharsets.UTF_8));
        //문자열 configSecretKey를 byte 배열로 변환 -> byte 배열을 JWT 서명용 SecretKey 객체로 변환

        this.accessExpiration = Duration.ofMillis(accessExpiration);
        //millisecond 값을 java duration 객체로 변환

        this.refreshExpiration = Duration.ofMillis(refreshExpiration);
    }

    // AccessToken 생성 (신원 + 권한 + 타입)
    public String createAccessToken(AuthMember authMember) {
        // 인가 정보
        String authorities = authMember.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        return baseBuilder(authMember.getUsername(), accessExpiration)
                .claim(CLAIM_TYPE, TOKEN_TYPE_ACCESS)
                .claim("role", authorities)
                .claim("email", authMember.getUsername())
                .signWith(secretKey)
                .compact();
    }

    // RefreshToken 생성 (재발급 전용이라 최소 정보만)
    public String createRefreshToken(AuthMember authMember){
        return baseBuilder(authMember.getUsername(), refreshExpiration)
                .claim(CLAIM_TYPE, TOKEN_TYPE_REFRESH)
                .signWith(secretKey)
                .compact();
    }

    // 공통 부분(subject, 발급/만료 시각) 세팅
    private JwtBuilder baseBuilder(String subject, Duration expiration) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString()) // 토큰 고유 id(jti), 같은 초 발급 토큰 구분
                .subject(subject) // User 이메일을 Subject로(getUsername -> 이메일 반환)
                .issuedAt(Date.from(now)) // 언제 발급한지
                .expiration(Date.from(now.plus(expiration))); // 언제까지 유효한지
    }

    // 토큰 정보 가져오기
    private Jws<Claims> getClaims(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(secretKey)
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(token);
    }

    //토큰에서 이메일 가져오기
    public String getEmailFromToken(String token){
        try {
            return getClaims(token).getPayload().getSubject(); // Parsing해서 Subject 가져오기
        } catch (JwtException e) {
            return null;
        }
    }

    //토큰 유효성 확인(매개변수 token의 유효성 확인 true/false)
    public boolean isValid(String token){
        try{
            getClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    //access 토큰 여부 확인
    public boolean isAccessToken(String token) {
        try {
            Claims claims = getClaims(token).getPayload();
            //type이 AccessToken과 일치하는지 확인
            return TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
        }
        catch (JwtException e){
            return false;
        }
    }

    //Refresh 토큰 여부 확인
    public boolean isRefreshToken(String token) {
        try {
            Claims claims = getClaims(token).getPayload();
            //type이 RefreshToken과 일치하는지 확인
            return TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
        }
        catch (JwtException e){
            return false;
        }
    }
}
