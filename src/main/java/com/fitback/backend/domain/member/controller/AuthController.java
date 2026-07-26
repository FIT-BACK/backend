package com.fitback.backend.domain.member.controller;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.service.AuthService;
import com.fitback.backend.domain.member.service.PasswordResetService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AuthController {
    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    @Operation(summary = "이메일 회원가입", description = "이메일과 비밀번호를 이용한 회원가입.\n" +
            "프로필 이미지, 닉네임, 관심 스타일 태그는 회원가입 직후 /members/me/onboarding에서 별도 설정.")
    @PostMapping("/v1/auth/sign")
    public ResponseEntity<ApiResponse<MemberResponse.SignUpResponse>> signUp(
            @Valid @RequestBody MemberRequest.SignUpRequest signUpDto)
    {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.onCreated(authService.signUp(signUpDto)));
    }

    @Operation(summary = "이메일 로그인", description = "이메일과 비밀번호를 검증하여 로그인 처리")
    @PostMapping("/v1/auth/login")
    public ApiResponse<MemberResponse.LoginResponse> login(
            @Valid @RequestBody MemberRequest.LoginRequest loginDto)
    {
        return ApiResponse.onSuccess(authService.login(loginDto));
    }

    @Operation(summary = "비밀번호 재설정 링크 전송", description = "가입 여부 노출 방지를 위해 이메일 존재 여부에 관계없이 동일한 응답을 반환")
    @PostMapping("/v1/auth/password-reset/request")
    public ApiResponse<Void> requestPasswordReset(
            @Valid @RequestBody MemberRequest.PasswordResetLinkRequest request
    ) {
        passwordResetService.requestResetLink(request);
        return ApiResponse.onSuccess(null);
    }

    @Operation(summary = "비밀번호 재설정", description = "이메일로 전달받은 재설정 토큰을 검증하고 새 비밀번호로 변경")
    @PatchMapping("/v1/auth/password-reset")
    public ApiResponse<Void> resetPassword(
            @Valid @RequestBody MemberRequest.PasswordResetRequest request
    ) {
        passwordResetService.resetPassword(request);
        return ApiResponse.onSuccess(null);
    }

    @Operation(summary = "토큰 재발급", description = "refresh token을 request body 로 받아 access token 재발급")
    @PostMapping("/v1/auth/token/refresh")
    public ApiResponse<MemberResponse.TokenResponse> refresh(
            @Valid @RequestBody MemberRequest.RefreshRequest refreshDto
    ){
        return ApiResponse.onSuccess(authService.refresh(refreshDto));
    }

    @Operation(summary = "카카오 임시 토큰 교환", description = "카카오 로그인 후 받은 임시 토큰을 실제 access/refresh 토큰으로 교환")
    @PostMapping("/v1/auth/token/exchange")
    public ApiResponse<MemberResponse.TokenExchangeResponse> exchange(
            @Valid @RequestBody MemberRequest.TokenExchangeRequest request
    ){
        return ApiResponse.onSuccess(authService.exchangeToken(request.tempToken()));
    }

    @Operation(summary = "로그아웃", description = "(인증필요) refresh token을 무효화 하는 로그아웃")
    @PostMapping("/v1/auth/logout")
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal AuthMember authMember
    )
    {
        authService.logout(authMember);
        return ApiResponse.onSuccess(null);
    }

}
