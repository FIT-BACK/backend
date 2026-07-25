package com.fitback.backend.domain.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedProductResponse(
        Long productId,
        boolean isSaved,
        Instant savedAt
) {

    public static SavedProductResponse saved(Long productId, Instant savedAt) {
        return new SavedProductResponse(productId, true, savedAt);
    }

    public static SavedProductResponse unsaved(Long productId) {
        return new SavedProductResponse(productId, false, null);
    }
}
