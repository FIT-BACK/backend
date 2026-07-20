package com.fitback.backend.domain.product.service.model;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record ReferenceProductCandidate(
        ProviderProductRef providerRef,
        String name,
        String brand,
        Money regularPrice,
        Money currentPrice,
        URI sourceUrl,
        URI imageUrl,
        ReferenceEvidenceType evidenceType,
        BigDecimal providerConfidence,
        Instant observedAt
) {

    public ReferenceProductCandidate {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        name = ModelValidation.requireNonBlank(name, "name");
        brand = ModelValidation.validateNullableText(brand, "brand");
        validateSameCurrency(regularPrice, currentPrice);
        sourceUrl = ModelValidation.requireHttpUri(sourceUrl, "sourceUrl");
        imageUrl = ModelValidation.validateNullableHttpUri(imageUrl, "imageUrl");
        Objects.requireNonNull(evidenceType, "evidenceType must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    private static void validateSameCurrency(Money regularPrice, Money currentPrice) {
        if (regularPrice != null && currentPrice != null
                && !regularPrice.currency().equals(currentPrice.currency())) {
            throw new IllegalArgumentException("reference prices must use the same currency");
        }
    }
}
