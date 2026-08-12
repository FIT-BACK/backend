package com.fitback.backend.domain.recommendation.dto;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import java.util.List;
import java.util.Objects;

public record BrowserRerankingHandoff(
        ProductCategory category,
        List<BrowserRerankingCandidate> candidates
) {

    public static final int MAX_CANDIDATES = 30;

    public BrowserRerankingHandoff {
        Objects.requireNonNull(category, "category must not be null");
        candidates = List.copyOf(candidates);
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("browser reranking candidates must not exceed 30");
        }
    }
}
