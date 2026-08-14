package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.notification.service.NotificationSettingService;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.security.token.TempTokenPayload;
import com.fitback.backend.global.security.token.TempTokenStore;
import com.fitback.backend.global.security.util.JwtUtil;
import com.fitback.backend.global.util.HmacUtil;
import com.fitback.backend.global.util.LowercaseNormalizer;
import com.fitback.backend.global.validation.BCryptPasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String EMAIL_UNIQUE_CONSTRAINT = "UK_MEMBER_EMAIL";
    private static final String REFRESH_TOKEN_HASH_CONTEXT = "refresh-token:";

    // 미가입·소셜 이메일도 실제 회원과 동일한 BCrypt 비용으로 검증하기 위한 고정 해시
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final JwtUtil jwtUtil;
    private final HmacUtil hmacUtil;
    private final TempTokenStore tempTokenStore;
    private final MemberProfileImageService memberProfileImageService;

    private final RejoinBlockChecker rejoinBlockChecker;
    private final NotificationSettingService notificationSettingService;
    private final LoginAttemptService loginAttemptService;

    //이메일 회원가입
    @Transactional
    public MemberResponse.SignUpResponse signUp(MemberRequest.SignUpRequest dto) {
        BCryptPasswordPolicy.validate(dto.password());
        String email = LowercaseNormalizer.normalize(dto.email());

        // 이메일 중복 검사
        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        //30일 재가입 차단 검사
        if (rejoinBlockChecker.isRejoinBlocked(email)) {
            throw new BusinessException(ErrorCode.REJOIN_BLOCKED);
        }

        //임시 닉네임 설정 (중복 방지)
        String temporalNickName;
        do {
            temporalNickName = "user_" + UUID.randomUUID().toString().substring(0, 8);
        } while (memberRepository.existsByNickname(temporalNickName));

        //비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(dto.password());

        //member 객체 생성 후 저장
        Member newMember = Member.create(email, temporalNickName, encodedPassword, LoginProvider.EMAIL);
        Member savedMember;
        try {
            // 동시 가입 요청은 사전 조회를 함께 통과할 수 있으므로 DB UNIQUE 제약으로 최종 중복 방지
            savedMember = memberRepository.saveAndFlush(newMember);
        } catch (DataIntegrityViolationException exception) {
            // 이메일 중복만 회원가입 비즈니스 예외로 변환
            if (isEmailUniqueViolation(exception)) {
                throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
            }
            throw exception;
        }

        //회원가입과 같은 트랜잭션에서 기본 알림 설정 row 생성
        notificationSettingService.createDefaultSetting(savedMember);

        //UserDetails 구현체인 authMember 생성
        AuthMember authMember = new AuthMember(savedMember);

        //AccessToken 발급
        String accessToken = jwtUtil.createAccessToken(authMember);

        //RefreshToken 원문은 응답으로 전달하고 HMAC 해시만 저장
        String refreshToken = jwtUtil.createRefreshToken(authMember);
        savedMember.updateRefreshTokenHash(hashRefreshToken(refreshToken));

        // 미가입 상태에서 누적된 동일 이메일의 실패 기록 제거
        loginAttemptService.clear(email);

        return MemberResponse.toSignUpResponse(accessToken, refreshToken, savedMember);
    }

    private boolean isEmailUniqueViolation(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(
                        constraintViolation.getConstraintName()
                );
            }
            cause = cause.getCause();
        }

        return false;
    }


    //이메일 로그인 서비스 메서드
    @Transactional
    public MemberResponse.LoginResponse login(MemberRequest.LoginRequest dto) {
        String email = LowercaseNormalizer.normalize(dto.email());
        // 잠금 중이면 회원 존재 여부와 관계없이 비밀번호 검사 전에 차단
        loginAttemptService.assertLoginAllowed(email);

        // 미가입 이메일도 동일한 BCrypt 비교를 수행해 회원 존재 여부 노출 방지
        Member member = memberRepository.findByEmail(email).orElse(null);
        String storedPassword = member != null && member.getPassword() != null
                ? member.getPassword()
                : DUMMY_PASSWORD_HASH;
        boolean passwordMatches = passwordEncoder.matches(dto.password(), storedPassword);

        if (member == null || member.getPassword() == null || !passwordMatches) {
            boolean locked = loginAttemptService.recordFailure(email);
            // 1~4회는 기존 인증 실패, 5회차부터 로그인 잠금 응답
            throw new BusinessException(
                    locked ? ErrorCode.LOGIN_ATTEMPT_LOCKED : ErrorCode.INVALID_CREDENTIALS
            );
        }

        //토큰 생성을 위해 AuthMember 생성
        AuthMember authMember = new AuthMember(member);

        //AccessToken 발급
        String accessToken = jwtUtil.createAccessToken(authMember);

        //RefreshToken 발급
        String refreshToken = jwtUtil.createRefreshToken(authMember);

        //발급한 RefreshToken 원문 대신 HMAC 해시 저장
        member.updateRefreshTokenHash(hashRefreshToken(refreshToken));
        // 로그인 성공 시 이전 실패 횟수와 잠금 기록 제거
        loginAttemptService.clear(email);

        return MemberResponse.toLoginResponse(
                accessToken,
                refreshToken,
                member,
                memberProfileImageService.resolveProfileImageUrl(member)
        );
    }

    @Transactional
    public MemberResponse.TokenResponse refresh(MemberRequest.RefreshRequest dto) {

        String refreshToken = dto.refreshToken();

        //token 검증 (유효성 + refresh 타입)
        if (!jwtUtil.isValid(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        //이메일로 Member 찾기
        String email = LowercaseNormalizer.normalize(jwtUtil.getEmailFromToken(refreshToken));
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        //요청 토큰을 같은 방식으로 해시해 DB에 저장된 값과 상수 시간으로 비교
        if (!matchesRefreshTokenHash(refreshToken, member.getRefreshTokenHash())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        //새 token 발급 (회전) 및 저장
        AuthMember authMember = new AuthMember(member);
        String newAccessToken = jwtUtil.createAccessToken(authMember);
        String newRefreshToken = jwtUtil.createRefreshToken(authMember);
        member.updateRefreshTokenHash(hashRefreshToken(newRefreshToken));

        return MemberResponse.toTokenResponse(newAccessToken, newRefreshToken);
    }

    //로그아웃
    @Transactional
    public void logout(AuthMember authMember) {

        Long memberId = authMember.getMember().getId();

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //저장된 refresh token 해시 초기화
        member.clearRefreshTokenHash();
    }

    //카카오 임시 토큰을 실제 access/refresh 토큰으로 교환 (일회용)
    @Transactional
    public MemberResponse.TokenExchangeResponse exchangeToken(String tempToken) {
        TempTokenPayload payload = tempTokenStore.consume(tempToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TEMP_TOKEN));

        Member member = memberRepository.findById(payload.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        AuthMember authMember = new AuthMember(member);
        String accessToken = jwtUtil.createAccessToken(authMember);
        String refreshToken = jwtUtil.createRefreshToken(authMember);

        //RefreshToken 원문 대신 HMAC 해시 저장
        member.updateRefreshTokenHash(hashRefreshToken(refreshToken));

        return MemberResponse.toTokenExchangeResponse(accessToken, refreshToken, payload.isNewMember());
    }

    //다른 HMAC 사용처와 결과가 겹치지 않도록 Refresh Token 용도 문자열 포함
    private String hashRefreshToken(String refreshToken) {
        return hmacUtil.hashHex(REFRESH_TOKEN_HASH_CONTEXT + refreshToken);
    }

    private boolean matchesRefreshTokenHash(String refreshToken, String storedHash) {
        if (storedHash == null) {
            return false;
        }

        byte[] requestedHashBytes = hashRefreshToken(refreshToken).getBytes(StandardCharsets.US_ASCII);
        byte[] storedHashBytes = storedHash.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(requestedHashBytes, storedHashBytes);
    }

}
