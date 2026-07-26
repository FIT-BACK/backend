package com.fitback.backend.domain.trend.controller;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.service.TrendService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trends")
public class TrendController {

    private final TrendService trendService;

    @Operation(
            summary = "트렌드 콘텐츠 목록 조회",
            description = "홈 화면/트렌드 더보기 화면에 표시할 트렌드 콘텐츠 목록을 최신순으로 커서 기반 조회. "
                    + "tag를 전달하면 해당 태그가 등록된 트렌드만 조회. "
                    + "비로그인 조회를 허용하며 로그인한 경우 각 트렌드의 내 저장 여부를 함께 계산."
    )
    @GetMapping
    public ApiResponse<TrendResponse.TrendList> getTrends(
            @Positive @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "tag", required = false) String tag,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        Member member = authMember == null ? null : authMember.getMember();
        TrendResponse.TrendList response = trendService.getTrends(cursor, tag, member);
        return ApiResponse.onSuccess(response);
    }

    @Operation(
            summary = "트렌드 상세 조회",
            description = "트렌드 카드 선택 시 트렌드 콘텐츠의 제목, 이미지, 설명, 태그를 조회. "
                    + "비로그인 조회를 허용하며 로그인한 경우 내 저장 여부를 함께 계산."
    )
    @GetMapping("/{trendId}")
    public ApiResponse<TrendResponse.TrendDetail> getTrendDetail(
            @Positive @PathVariable("trendId") Long trendId,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        Member member = authMember == null ? null : authMember.getMember();
        TrendResponse.TrendDetail response = trendService.getTrendDetail(trendId, member);
        return ApiResponse.onSuccess(response);
    }
}
