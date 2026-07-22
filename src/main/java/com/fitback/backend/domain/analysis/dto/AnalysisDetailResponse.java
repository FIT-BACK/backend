package com.fitback.backend.domain.analysis.dto;

import java.util.List;

public record AnalysisDetailResponse(
        Long reportId,
        String imageUrl,
        Integer matchPercentage,
        List<String> tags,
        List<RecommendationGroupResponse> recommendationGroups
) {
}
