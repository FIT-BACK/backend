package com.fitback.backend.domain.member.dto;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import lombok.Builder;

// 회원 응답 DTO
public class MemberResponse {

    // 회원가입 응답
    @Builder
    public record SignUpResponse(
            String accessToken,
            String refreshToken,
            Long memberId,
            String email,
            LoginProvider loginProvider,
            MemberRole role
    ) {}

    @Builder
    public record LoginResponse(
            String accessToken,
            String refreshToken,
            Long memberId,
            String email,
            String nickname,
            String profileImageUrl,
            LoginProvider loginProvider
    ) {}

    //회원가입 응답 변환
    public static SignUpResponse toSignUpResponse(
            String accessToken,
            String refreshToken,
            Member member
    ){
        return SignUpResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .memberId(member.getId())
                .email(member.getEmail())
                .loginProvider(member.getLoginProvider())
                .role(member.getRole())
                .build();
    }

    //이메일 로그인 응답 DTO 변환
    public static LoginResponse toLoginResponse(
            String accessToken,
            String refreshToken,
            Member member
    ){
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .memberId(member.getId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .profileImageUrl(member.getProfileImageUrl())
                .loginProvider(member.getLoginProvider())
                .build();
    }
}
