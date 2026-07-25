package com.fitback.backend.domain.recommendation.dto;

import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import java.util.List;

public record RecommendationResultResponse(
        RecommendationStatus recommendationStatus,
        String scoreVersion,
        List<RecommendationGroupResponse> recommendationGroups,
        boolean partial,
        List<String> warnings
) {

    public RecommendationResultResponse {
        recommendationGroups = List.copyOf(recommendationGroups);
        warnings = List.copyOf(warnings);
    }
}
