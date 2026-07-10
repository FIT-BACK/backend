package com.fitback.backend.domain.member.controller;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.service.AuthService;
import com.fitback.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
            @Valid @RequestBody MemberRequest.SignUpRequest signUpdto)
    {
        return ApiResponse.onCreated(authService.signUp(signUpdto));
    }
}
