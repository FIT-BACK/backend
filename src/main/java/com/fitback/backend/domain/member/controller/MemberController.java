package com.fitback.backend.domain.member.controller;

import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.dto.MemberResponse;
import com.fitback.backend.domain.member.service.MemberService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "회원정보 수정", description = "마이페이지에서 현재 로그인한 회원의 닉네임과 프로필 이미지 URL을 수정, ")
    @PatchMapping("/v1/members/me")
    public ApiResponse<MemberResponse.UpdateMemberResponse> updateMember(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody MemberRequest.UpdateMemberRequest updateMemberDto)
    {
        return ApiResponse.onSuccess(memberService.updateMember(authMember, updateMemberDto));
    }

    @Operation(summary = "비밀번호 변경", description = "현재 로그인한 이메일 회원의 비밀번호를 변경")
    @PatchMapping("/v1/members/me/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody MemberRequest.ChangePasswordRequest changePasswordDto)
    {

        memberService.changePassword(authMember, changePasswordDto);
        return ApiResponse.onSuccess(null);
    }


}
