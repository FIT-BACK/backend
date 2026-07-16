package com.fitback.backend.domain.lookbook.controller;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.AuthMember;
import com.fitback.backend.global.response.ApiResponse;
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
@RequestMapping("/api/v1/lookbooks")
public class LookbookController {

    private final LookbookService lookbookService;

    @Operation(
            summary = "룩북 업로드",
            description = "로그인한 회원이 S3에 선 업로드한 원본 룩 이미지와 가성비 매칭 이미지 URL, "
                    + "기존 태그 ID, 선택 구매 링크 및 코멘트를 전달하여 룩북을 생성."
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
        return ApiResponse.onSuccess(response);
    }
}
