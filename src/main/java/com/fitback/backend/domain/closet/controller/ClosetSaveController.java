package com.fitback.backend.domain.closet.controller;

import com.fitback.backend.domain.closet.dto.ClosetSaveRequest;
import com.fitback.backend.domain.closet.dto.ClosetSaveResponse;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.service.ClosetSaveService;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/closet-saves")
public class ClosetSaveController {

    private final ClosetSaveService closetSaveService;

    @Operation(
            summary = "마이 클로젯 저장",
            description = "로그인한 회원이 트렌드/룩북을 마이 클로젯에 저장. "
                    + "분석리포트는 /api/v1/analyses/{reportId}/save를 사용."
    )
    @PostMapping
    public ApiResponse<ClosetSaveResponse.Created> saveCloset(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ClosetSaveRequest.Create request
    ) {
        ClosetSave closetSave = closetSaveService.save(requireMemberId(authMember), request);
        return ApiResponse.onCreated(ClosetSaveResponse.Created.from(closetSave));
    }

    @Operation(
            summary = "마이 클로젯 목록 조회",
            description = "로그인한 회원의 저장 목록을 최신순으로 커서 기반 조회. target_type 미지정 시 전체 조회."
    )
    @GetMapping
    public ApiResponse<ClosetSaveResponse.ClosetSaveList> getClosetSaves(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(name = "target_type", required = false) ClosetTargetType targetType,
            @Positive @RequestParam(name = "cursor", required = false) Long cursor
    ) {
        ClosetSaveResponse.ClosetSaveList response = closetSaveService.getClosetSaves(
                requireMemberId(authMember), targetType, cursor);
        return ApiResponse.onSuccess(response);
    }

    @Operation(
            summary = "마이 클로젯 저장 취소",
            description = "로그인한 회원이 본인이 저장한 항목을 삭제."
    )
    @DeleteMapping("/{saveId}")
    public ApiResponse<Void> cancelClosetSave(
            @Positive @PathVariable("saveId") Long saveId,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        closetSaveService.cancel(requireMemberId(authMember), saveId);
        return ApiResponse.onSuccess();
    }

    private Long requireMemberId(AuthMember authMember) {
        if (authMember == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return authMember.getMember().getId();
    }
}
