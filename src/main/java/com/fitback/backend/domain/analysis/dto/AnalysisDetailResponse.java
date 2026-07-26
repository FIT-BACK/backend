package com.fitback.backend.domain.analysis.dto;

import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import java.time.LocalDateTime;
import java.util.List;

public record AnalysisDetailResponse(
        Long reportId,
        String originalImageId,
        String imageUrl,
        Integer matchPercentage,
        List<String> tags,
        RecommendationStatus recommendationStatus,
        String scoreVersion,
        List<RecommendationGroupResponse> recommendationGroups,
        boolean saved,
        LocalDateTime savedAt,
        List<SavedAnalysisItemResponse> selectedItems
) {
}
