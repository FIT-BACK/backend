package com.fitback.backend.domain.tag.controller;

import com.fitback.backend.domain.tag.dto.TagResponse;
import com.fitback.backend.domain.tag.service.TagService;
import com.fitback.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tagService;

    @Operation(
            summary = "태그 목록 조회",
            description = "회원 관심 태그, 분석 태그 수정, 룩북 업로드 태그 입력에 사용할 태그 목록을 조회."
    )
    @GetMapping
    public ApiResponse<TagResponse.TagList> getTags() {
        return ApiResponse.onSuccess(tagService.getTags());
    }
}
