package com.fitback.backend.domain.tag.controller;

import com.fitback.backend.domain.tag.dto.TagListResponse;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.service.TagService;
import com.fitback.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tagService;

    @Operation(
            summary = "태그 목록 조회",
            description = "태그 선택·자동완성 화면에서 사용할 태그를 이름순으로 조회. "
                    + "태그 유형과 이름 일부를 선택적으로 필터링하며 최대 50개를 반환."
    )
    @GetMapping
    public ApiResponse<TagListResponse> getTags(
            @RequestParam(required = false) TagType tagType,
            @RequestParam(required = false) @Size(max = 50) String query,
            @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit
    ) {
        return ApiResponse.onSuccess(tagService.getTags(tagType, query, limit));
    }
}
