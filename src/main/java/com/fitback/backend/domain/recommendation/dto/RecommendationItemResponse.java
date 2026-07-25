package com.fitback.backend.domain.recommendation.dto;

import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import java.math.BigDecimal;
import java.util.List;

public record RecommendationItemResponse(
        Long productId,
        Integer rank,
        String imageUrl,
        String name,
        String sellerName,
        ProductPriceResponse price,
        String purchaseUrl,
        BigDecimal similarityScore,
        BigDecimal finalScore,
        List<String> reasonCodes,
        ProductAvailability availability,
        boolean isSaved
) {

    public RecommendationItemResponse {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
