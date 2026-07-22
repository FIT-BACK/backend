package com.fitback.backend.domain.analysis.dto;

public record RecommendationItemResponse(
        Integer rank,
        String imageUrl,
        String name,
        String sellerName,
        Integer price,
        String purchaseUrl
) {
}
