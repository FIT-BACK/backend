package com.fitback.backend.domain.recommendation.dto;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Objects;

public record BrowserRerankingCandidate(
        String candidateId,
        URI imageUrl,
        BigDecimal tagSimilarity
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
    }
}
