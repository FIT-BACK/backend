package com.fitback.backend.domain.recommendation.dto;

import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Objects;

public record BrowserRerankingCandidate(
        String candidateId,
        URI imageUrl,
        BigDecimal tagSimilarity,
        String name,
        String sellerName,
        ProductPriceResponse price,
        URI purchaseUrl
) {

    public BrowserRerankingCandidate {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        Objects.requireNonNull(imageUrl, "imageUrl must not be null");
        Objects.requireNonNull(tagSimilarity, "tagSimilarity must not be null");
        if (tagSimilarity.compareTo(BigDecimal.ZERO) < 0
                || tagSimilarity.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("tagSimilarity must be between 0 and 1");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
