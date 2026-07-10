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

    //응답 DTO로 변환
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
}
