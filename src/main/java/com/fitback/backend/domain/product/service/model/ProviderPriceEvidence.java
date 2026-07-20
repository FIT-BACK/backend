package com.fitback.backend.domain.product.service.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record ProviderPriceEvidence(
        Money regularPrice,
        Money currentPrice,
        URI sourceUrl,
        Instant observedAt
) {

    public ProviderPriceEvidence {
        if (regularPrice == null && currentPrice == null) {
            throw new IllegalArgumentException("at least one verified price is required");
        }
        if (regularPrice != null && currentPrice != null
                && !regularPrice.currency().equals(currentPrice.currency())) {
            throw new IllegalArgumentException("verified prices must use the same currency");
        }
        sourceUrl = ModelValidation.requireHttpUri(sourceUrl, "sourceUrl");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
