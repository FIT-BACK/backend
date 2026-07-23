package com.fitback.backend.domain.analysis.dto;

import java.util.List;

public record RecommendationGroupResponse(
        String category,
        List<RecommendationItemResponse> items
) {
}
