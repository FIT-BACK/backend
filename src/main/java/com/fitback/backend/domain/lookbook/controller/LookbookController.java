package com.fitback.backend.domain.lookbook.controller;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.AuthMember;
import com.fitback.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/lookbooks")
public class LookbookController {

    private final LookbookService lookbookService;

    @Operation(
            summary = "룩북 업로드",
            description = "로그인한 회원이 S3에 선 업로드한 원본 룩 이미지와 가성비 매칭 이미지 URL, "
                    + "1개 이상 5개 이하의 중복되지 않는 태그 ID, 선택 구매 링크 및 코멘트를 전달하여 룩북을 생성."
    )
    @PostMapping
    public ApiResponse<LookbookResponse.LookbookCreate> createLookbook(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody LookbookRequest.LookbookCreate request
    ) {
        if (authMember == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        LookbookResponse.LookbookCreate response = lookbookService.createLookbook(
                authMember.getMember(),
                request
        );
        return ApiResponse.onCreated(response);
    }

    @Operation(
            summary = "룩북 삭제",
            description = "룩북 작성자 또는 ADMIN 권한을 가진 회원이 룩북을 soft delete 방식으로 삭제."
    )
    @DeleteMapping("/{lookbookId}")
    public ApiResponse<Void> deleteLookbook(
            @PathVariable("lookbookId") Long lookbookId,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        if (authMember == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        lookbookService.deleteLookbook(lookbookId, authMember.getMember());
        return ApiResponse.onSuccess();
    }

    @Operation(
            summary = "룩북 좋아요",
            description = "로그인한 회원이 룩북에 좋아요를 등록, 변경된 좋아요 수를 반환. "
                    + "이미 좋아요한 룩북에 다시 요청해도 현재 좋아요 수와 함께 성공 응답을 반환."
    )
    @PostMapping("/{lookbookId}/like")
    public ApiResponse<LookbookResponse.LookbookLike> likeLookbook(
            @PathVariable("lookbookId") Long lookbookId,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        if (authMember == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        LookbookResponse.LookbookLike response = lookbookService.likeLookbook(
                lookbookId,
                authMember.getMember()
        );
        return ApiResponse.onSuccess(response);
    }

    @Operation(
            summary = "룩북 좋아요 취소",
            description = "로그인한 회원의 룩북 좋아요를 hard delete 방식으로 삭제하고 변경된 좋아요 수를 반환. "
                    + "좋아요하지 않은 룩북에 다시 요청해도 현재 좋아요 수와 함께 성공 응답을 반환."
    )
    @DeleteMapping("/{lookbookId}/like")
    public ApiResponse<LookbookResponse.LookbookLike> deleteLookbookLike(
            @PathVariable("lookbookId") Long lookbookId,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        if (authMember == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        LookbookResponse.LookbookLike response = lookbookService.deleteLookbookLike(
                lookbookId,
                authMember.getMember()
        );
        return ApiResponse.onSuccess(response);
    }

    @Operation(
            summary = "룩북 목록 조회",
            description = "룩북을 최신순으로 요청한 pageSize만큼 커서 기반 조회. pageSize 기본값은 20. "
                    + "비로그인 조회를 허용. "
                    + "로그인한 경우 각 룩북의 내 좋아요 여부를 함께 계산."
    )
    @GetMapping
    public ApiResponse<LookbookResponse.LookbookList> getLookbooks(
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20")
            Integer pageSize,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        Member member = authMember == null ? null : authMember.getMember();
        LookbookResponse.LookbookList response = lookbookService.getLookbooks(
                cursor,
                pageSize,
                member
        );
        return ApiResponse.onSuccess(response);
    }

    @Operation(
            summary = "룩북 상세 조회",
            description = "룩북의 원본 이미지, 매칭 이미지, 작성자 닉네임, 태그, 구매 링크와 좋아요 정보를 조회. "
                    + "비로그인 조회를 허용하며 로그인한 경우 isLiked 를 통해 내 좋아요 여부를 함께 계산."
    )
    @GetMapping("/{lookbookId}")
    public ApiResponse<LookbookResponse.LookbookDetail> getLookbookDetail(
            @PathVariable("lookbookId") Long lookbookId,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        Member member = authMember == null ? null : authMember.getMember();
        LookbookResponse.LookbookDetail response = lookbookService.getLookbookDetail(
                lookbookId,
                member
        );
        return ApiResponse.onSuccess(response);
    }
}
