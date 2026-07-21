package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.repository.WithdrawnEmailBlockRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.security.util.JwtUtil;
import com.fitback.backend.global.util.HmacUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final JwtUtil jwtUtil;

    private final WithdrawnEmailBlockRepository withdrawnEmailBlockRepository;
    private final HmacUtil hmacUtil;

    //이메일 회원가입
    @Transactional
    public MemberResponse.SignUpResponse signUp(MemberRequest.SignUpRequest dto) {

        // 이메일 중복 검사
        if (memberRepository.existsByEmail(dto.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        //30일 재가입 차단 검사
        assertNotRejoinBlocked(dto.email());

        //임시 닉네임 설정 (중복 방지)
        String temporalNickName;
        do {
            temporalNickName = "user_" + UUID.randomUUID().toString().substring(0, 8);
        } while (memberRepository.existsByNickname(temporalNickName));

        //비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(dto.password());

        //member 객체 생성 후 저장
        Member newMember = Member.create(dto.email(), temporalNickName, encodedPassword, LoginProvider.EMAIL);
        Member savedMember = memberRepository.save(newMember);

        //UserDetails 구현체인 authMember 생성
        AuthMember authMember = new AuthMember(savedMember);

        //AccessToken 발급
        String accessToken = jwtUtil.createAccessToken(authMember);

        //RefreshToken 발급 / 저장
        String refreshToken = jwtUtil.createRefreshToken(authMember);
        savedMember.updateRefreshToken(refreshToken);

        return MemberResponse.toSignUpResponse(accessToken, refreshToken, savedMember);
    }


    //이메일 로그인 서비스 메서드
    @Transactional
    public MemberResponse.LoginResponse login(MemberRequest.LoginRequest dto) {

        //이메일로 member 찾기
        Member member = memberRepository.findByEmail(dto.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        //비밀번호 일치 여부 확인
        if(member.getPassword() == null || !passwordEncoder.matches(dto.password(), member.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        //토큰 생성을 위해 AuthMember 생성
        AuthMember authMember = new AuthMember(member);

        //AccessToken 발급
        String accessToken = jwtUtil.createAccessToken(authMember);

        //RefreshToken 발급
        String refreshToken = jwtUtil.createRefreshToken(authMember);

        //발급한 RefreshToken 저장
        member.updateRefreshToken(refreshToken);

        return MemberResponse.toLoginResponse(accessToken, refreshToken, member);
    }

    @Transactional
    public MemberResponse.RefreshResponse refresh(MemberRequest.RefreshRequest dto) {

        String refreshToken = dto.refreshToken();

        //token 검증 (유효성 + refresh 타입)
        if (!jwtUtil.isValid(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        //이메일로 Member 찾기
        String email = jwtUtil.getEmailFromToken(refreshToken);
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        //저장된 refresh token과 요청 토큰 일치 확인 (요청 토큰을 앞에 두어 null 안전)
        if (!refreshToken.equals(member.getRefreshToken())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        //새 token 발급 (회전) 및 저장
        AuthMember authMember = new AuthMember(member);
        String newAccessToken = jwtUtil.createAccessToken(authMember);
        String newRefreshToken = jwtUtil.createRefreshToken(authMember);
        member.updateRefreshToken(newRefreshToken);

        return MemberResponse.toRefreshResponse(newAccessToken, newRefreshToken);
    }

    //로그아웃
    @Transactional
    public void logout(AuthMember authMember) {

        Long memberId = authMember.getMember().getId();

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        //refresh token 초기화
        member.clearRefreshToken();
    }

    //탈퇴 후 30일 재가입 차단 검사 (이메일·소셜 가입 공용)
    private void assertNotRejoinBlocked(String email) {
        String hashedEmail = hmacUtil.hashHex(email);
        if (withdrawnEmailBlockRepository.existsByEmailHashAndBlockedUntilAfter(hashedEmail, LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.REJOIN_BLOCKED);
        }
    }
}
