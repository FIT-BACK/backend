package com.fitback.backend.domain.closet.controller;

import com.fitback.backend.domain.closet.dto.ClosetSaveRequest;
import com.fitback.backend.domain.closet.dto.ClosetSaveResponse;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.service.ClosetSaveService;
import com.fitback.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
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
            description = "트렌드/룩북/분석리포트를 마이 클로젯에 저장. memberId는 JWT 도입 전까지 임시로 쿼리 파라미터로 전달."
    )
    @PostMapping
    public ApiResponse<Void> saveCloset(
            @Positive @RequestParam(name = "memberId") Long memberId,
            @Valid @RequestBody ClosetSaveRequest.Create request
    ) {
        closetSaveService.save(memberId, request);
        return ApiResponse.onCreated();
    }

    @Operation(
            summary = "마이 클로젯 목록 조회",
            description = "현재 회원의 저장 목록을 최신순으로 커서 기반 조회. target_type 미지정 시 전체 조회. "
                    + "memberId는 JWT 도입 전까지 임시로 쿼리 파라미터로 전달."
    )
    @GetMapping
    public ApiResponse<ClosetSaveResponse.ClosetSaveList> getClosetSaves(
            @Positive @RequestParam(name = "memberId") Long memberId,
            @RequestParam(name = "target_type", required = false) ClosetTargetType targetType,
            @Positive @RequestParam(name = "cursor", required = false) Long cursor
    ) {
        ClosetSaveResponse.ClosetSaveList response = closetSaveService.getClosetSaves(memberId, targetType, cursor);
        return ApiResponse.onSuccess(response);
    }

    @Operation(
            summary = "마이 클로젯 저장 취소",
            description = "저장한 항목을 삭제. memberId는 JWT 도입 전까지 임시로 쿼리 파라미터로 전달."
    )
    @DeleteMapping("/{saveId}")
    public ApiResponse<Void> cancelClosetSave(
            @Positive @PathVariable("saveId") Long saveId,
            @Positive @RequestParam(name = "memberId") Long memberId
    ) {
        closetSaveService.cancel(memberId, saveId);
        return ApiResponse.onSuccess();
    }
}
