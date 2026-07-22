package com.fitback.backend.domain.product.service.model;

import java.time.Duration;

public record ProviderCapabilities(
        String provider,
        boolean supportsImageSearch,
        boolean supportsKeywordSearch,
        boolean supportsLookup,
        boolean stableProductId,
        boolean stableVariantId,
        boolean canPersistResult,
        boolean canPersistPrice,
        boolean canPersistImageUrl,
        boolean requiresLiveLookup,
        Duration maxTtl,
        boolean wishlistSupported
) {

    public ProviderCapabilities {
        provider = ModelValidation.requireNonBlank(provider, "provider");
        if (maxTtl != null && (maxTtl.isNegative() || maxTtl.isZero())) {
            throw new IllegalArgumentException("maxTtl must be positive when provided");
        }
    }
}
