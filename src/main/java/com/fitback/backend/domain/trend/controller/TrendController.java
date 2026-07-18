package com.fitback.backend.domain.trend.controller;

import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.service.TrendService;
import com.fitback.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/trends")
public class TrendController {

    private final TrendService trendService;

    @Operation(
            summary = "트렌드 콘텐츠 목록 조회",
            description = "홈 화면의 요즘 트렌드 영역에 표시할 트렌드 콘텐츠 목록을 최신순으로 커서 기반 조회."
    )
    @GetMapping
    public ApiResponse<TrendResponse.TrendList> getTrends(
            @Positive @RequestParam(name = "cursor", required = false) Long cursor
    ) {
        TrendResponse.TrendList response = trendService.getTrends(cursor);
        return ApiResponse.onSuccess(response);
    }

    @Operation(
            summary = "트렌드 상세 조회",
            description = "트렌드 카드 선택 시 트렌드 콘텐츠의 제목, 이미지, 설명, 태그를 조회."
    )
    @GetMapping("/{trendId}")
    public ApiResponse<TrendResponse.TrendDetail> getTrendDetail(
            @Positive @PathVariable("trendId") Long trendId
    ) {
        TrendResponse.TrendDetail response = trendService.getTrendDetail(trendId);
        return ApiResponse.onSuccess(response);
    }
}
