package com.fitback.backend.domain.member.controller;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.service.AuthService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AuthController {
    private final AuthService authService;

    @Operation(summary = "이메일 회원가입", description = "이메일과 비밀번호를 이용한 회원가입.\n" +
            "프로필 이미지, 닉네임, 관심 스타일 태그는 회원가입 직후 /members/me/onboarding에서 별도 설정.")
    @PostMapping("/v1/auth/sign")
    public ApiResponse<MemberResponse.SignUpResponse> signUp(
            @Valid @RequestBody MemberRequest.SignUpRequest signUpDto)
    {
        return ApiResponse.onCreated(authService.signUp(signUpDto));
    }

    @Operation(summary = "이메일 로그인", description = "이메일과 비밀번호를 검증하여 로그인 처리")
    @PostMapping("/v1/auth/login")
    public ApiResponse<MemberResponse.LoginResponse> login(
            @Valid @RequestBody MemberRequest.LoginRequest loginDto)
    {
        return ApiResponse.onSuccess(authService.login(loginDto));
    }

    @Operation(summary = "토큰 재발급", description = "refresh token을 request body 로 받아 access token 재발급")
    @PostMapping("/v1/auth/token/refresh")
    public ApiResponse<MemberResponse.RefreshResponse> refresh(
            @Valid @RequestBody MemberRequest.RefreshRequest refreshDto
    ){
        return ApiResponse.onSuccess(authService.refresh(refreshDto));
    }

    @Operation(summary = "로그아웃", description = "refresh token을 무효화 하는 로그아웃")
    @PostMapping("/v1/auth/logout")
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal AuthMember authMember
    )
    {
        authService.logout(authMember);
        return ApiResponse.onSuccess(null);
    }
}

