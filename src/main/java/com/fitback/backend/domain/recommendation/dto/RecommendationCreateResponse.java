package com.fitback.backend.domain.recommendation.dto;

import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import java.util.List;
import java.util.Objects;

public record RecommendationCreateResponse(
        Long reportId,
        List<String> analysisTags,
        Integer matchPercentage,
        String scoreVersion,
        RecommendationStatus recommendationStatus,
        List<RecommendationGroupResponse> recommendationGroups,
        BrowserRerankingHandoff browserReranking,
        boolean partial,
        List<String> warnings
) {

    public RecommendationCreateResponse {
        analysisTags = List.copyOf(analysisTags);
        recommendationGroups = List.copyOf(recommendationGroups);
        browserReranking = Objects.requireNonNull(browserReranking, "browserReranking must not be null");
        warnings = List.copyOf(warnings);
    }
}
