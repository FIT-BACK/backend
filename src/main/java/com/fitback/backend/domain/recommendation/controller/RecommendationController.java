package com.fitback.backend.domain.recommendation.controller;

import com.fitback.backend.domain.recommendation.dto.RecommendationCreateResponse;
import com.fitback.backend.domain.recommendation.service.RecommendationService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.CurrentMemberProvider;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/analyses/{reportId}/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final CurrentMemberProvider currentMemberProvider;

    @PostMapping
    public ApiResponse<RecommendationCreateResponse> generate(
            @PathVariable @Positive Long reportId
    ) {
        return ApiResponse.onSuccess(recommendationService.generate(
                currentMemberProvider.getCurrentMemberId(),
                reportId
        ));
    }
}
