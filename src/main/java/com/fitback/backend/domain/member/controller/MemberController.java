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
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "회원정보 수정", description = "(인증필요) 마이페이지에서 현재 로그인한 회원의 닉네임과 프로필 이미지 URL을 수정, ")
    @PatchMapping("/v1/members/me")
    public ApiResponse<MemberResponse.UpdateMemberResponse> updateMember(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody MemberRequest.UpdateMemberRequest updateMemberDto)
    {
        return ApiResponse.onSuccess(memberService.updateMember(authMember, updateMemberDto));
    }

    @Operation(summary = "비밀번호 변경", description = "(인증필요) 현재 로그인한 이메일 회원의 비밀번호를 변경")
    @PatchMapping("/v1/members/me/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody MemberRequest.ChangePasswordRequest changePasswordDto)
    {

        memberService.changePassword(authMember, changePasswordDto);
        return ApiResponse.onSuccess(null);
    }

    @Operation(summary = "마이페이지", description = "(인증필요) 현재 로그인한 회원의 마이페이지 정보를 조회\n" +
            "회원 기본 정보와 와이어프레임에 표시되는 저장 수, 분석 수, 업로드 수 반환")
    @GetMapping("/v1/members/me")
    public ApiResponse<MemberResponse.MyPageResponse> myPage(
            @AuthenticationPrincipal AuthMember authMember
    ){
        return ApiResponse.onSuccess(memberService.myPage(authMember));
    }

    @Operation(summary = "회원 탈퇴", description = "(인증필요) 현재 로그인한 회원이 자신의 계정을 탈퇴")
    @DeleteMapping("/v1/members/me")
    public ApiResponse<Void> deleteAccount(
            @AuthenticationPrincipal AuthMember authMember
    )
    {
        memberService.deleteAccount(authMember);
        return ApiResponse.onSuccess(null);
    }

    @Operation(summary = "회원가입 시 프로필 설정", description = "(인증 필요) 회원가입 또는 카카오 신규 로그인 이후 프로필 설정 화면에서 입력한 정보를 저장하는 API (와이어 프레임의 프로필 설정 기능)")
    @PutMapping("/v1/members/me/onboarding")
    public ApiResponse<MemberResponse.OnboardingResponse> onboarding(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody MemberRequest.OnboardingRequest onboardingRequestDto
    )
    {
        return ApiResponse.onSuccess(memberService.onboarding(authMember, onboardingRequestDto));
    }

    @Operation(summary = "내 관심 태그 수정", description = "(인증 필요) 관심 태그 목록을 수정 (덮어쓰기)")
    @PutMapping("/v1/members/me/tags")
    public ApiResponse<MemberResponse.UpdateTagsResponse> updateTags(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody MemberRequest.UpdateTagsRequest updateTagsRequestDto
    )
    {
        return ApiResponse.onSuccess(memberService.updateTags(authMember, updateTagsRequestDto));
    }
}
