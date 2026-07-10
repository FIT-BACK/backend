package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import com.fitback.backend.global.security.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final JwtUtil jwtUtil;

    //회원가입
    @Transactional
    public MemberResponse.SignUpResponse signUp(MemberRequest.SignUpRequest dto) {

        // 이메일 중복 검사
        if (memberRepository.existsByEmail(dto.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        //임시 닉네임 설정
        String temporalNickName = "user_" + UUID.randomUUID().toString().substring(0, 8);

        //비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(dto.password());

        //member 객체 생성 후 저장
        Member newMember = Member.create(dto.email(), temporalNickName, encodedPassword, LoginProvider.EMAIL);
        Member savedMember = memberRepository.save(newMember);

        //UserDetails 구현체인 authMember 생성
        AuthMember authMember = new AuthMember(savedMember);

        //AccessToken 발급
        String accessToken = jwtUtil.createAccessToken(authMember);

        //RefreshToken 발급
        String refreshToken = jwtUtil.createRefreshToken(authMember);

        return MemberResponse.toSignUpResponse(accessToken, refreshToken, savedMember);
    }
}
