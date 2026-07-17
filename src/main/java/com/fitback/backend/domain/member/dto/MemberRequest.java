package com.fitback.backend.domain.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// 회원 요청 DTO
public class MemberRequest {

    // 이메일 회원가입 요청
    public record SignUpRequest(
            @NotBlank(message = "이메일은 필수 입력값입니다.")
            @Email(message = "올바른 이메일 형식이 아닙니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수 입력값입니다.")
            String password
    ) {}

    //이메일 로그인 요청
    public record LoginRequest(
            @NotBlank(message = "이메일은 필수 입력값입니다.")
            @Email(message = "올바른 이메일 형식이 아닙니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수 입력값입니다.")
            String password
    ) {}

    //토큰 재발급
    public record RefreshRequest(
            @NotBlank(message = "Refresh Token은 필수 입니다.")
            String refreshToken
    ) {}

    //회원정보 수정 (부분 수정: 전달된 필드만 반영, 미전송/null 필드는 기존 값 유지)
    public record UpdateMemberRequest(
            String nickname,
            String profileImageUrl
    ) {}

    //비밀번호 변경
    public record ChangePasswordRequest (
            @NotBlank(message = "현재 비밀번호는 필수 입력값 입니다.")
            String currentPassword,

            @NotBlank(message = "새 비밀번호는 필수 입력값 입니다.")
            String newPassword
    ){}

    //회원 가입 프로필 설정
    public record OnboardingRequest(
            @NotBlank(message = "닉네임은 필수 입력값 입니다.")
            String nickname,
            String profileImageUrl,
            @NotNull(message = "관심 태그 필드가 포함되어야 합니다. (빈 배열 허용)")
            List<Long> tagIds
    ) {}

    //내 관심 태그 수정
    public record UpdateTagsRequest(
            @NotNull(message = "관심 태그 필드가 포함되어야 합니다. (빈 배열 허용)")
            List<Long> tagIds
    ) {}
}
