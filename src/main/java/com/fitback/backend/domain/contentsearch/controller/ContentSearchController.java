package com.fitback.backend.domain.contentsearch.controller;

import com.fitback.backend.domain.contentsearch.dto.ContentSearchResponse;
import com.fitback.backend.domain.contentsearch.service.ContentSearchService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/content-search")
public class ContentSearchController {

    private final ContentSearchService contentSearchService;

    public ContentSearchController(ContentSearchService contentSearchService) {
        this.contentSearchService = contentSearchService;
    }

    @Operation(
            summary = "트렌드·룩북 통합 검색",
            description = "트렌드와 공개 룩북을 텍스트로 검색하여 각 타입별 최신 10개를 반환."
    )
    @GetMapping
    public ApiResponse<ContentSearchResponse> search(
            @RequestParam("keyword") String keyword,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        Member member = authMember == null ? null : authMember.getMember();
        return ApiResponse.onSuccess(contentSearchService.search(keyword, member));
    }
}
