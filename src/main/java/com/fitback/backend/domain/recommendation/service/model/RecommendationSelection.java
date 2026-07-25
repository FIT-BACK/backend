package com.fitback.backend.domain.recommendation.service.model;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import java.math.BigDecimal;
import java.util.List;

public record RecommendationSelection(
        Long productId,
        Integer rankNo,
        ProductCategory category,
        BigDecimal similarityScore,
        BigDecimal finalScore,
        List<String> reasonCodes
) {

    public RecommendationSelection {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
