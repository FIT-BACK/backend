package com.fitback.backend.domain.product.service.model;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record ExternalProductCandidate(
        ProviderProductRef providerRef,
        String name,
        String brand,
        String categoryPath,
        ProductOffer offer,
        URI imageUrl,
        BigDecimal providerScore,
        Instant observedAt
) {

    public ExternalProductCandidate {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        name = ModelValidation.requireNonBlank(name, "name");
        brand = ModelValidation.validateNullableText(brand, "brand");
        categoryPath = ModelValidation.validateNullableText(categoryPath, "categoryPath");
        imageUrl = ModelValidation.validateNullableHttpUri(imageUrl, "imageUrl");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
