package com.fitback.backend.domain.recommendation.dto;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import java.util.List;

public record RecommendationGroupResponse(
        ProductCategory category,
        List<RecommendationItemResponse> items
) {

    public RecommendationGroupResponse {
        items = List.copyOf(items);
    }
}
