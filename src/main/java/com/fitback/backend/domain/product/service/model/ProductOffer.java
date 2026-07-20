package com.fitback.backend.domain.product.service.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public record ProductOffer(
        Money regularPrice,
        Money currentPrice,
        Money salePrice,
        ProductAvailability availability,
        String seller,
        URI purchaseUrl,
        URI affiliateUrl,
        Instant observedAt
) {

    public ProductOffer {
        Objects.requireNonNull(availability, "availability must not be null");
        seller = ModelValidation.validateNullableText(seller, "seller");
        purchaseUrl = ModelValidation.validateNullableHttpUri(purchaseUrl, "purchaseUrl");
        affiliateUrl = ModelValidation.validateNullableHttpUri(affiliateUrl, "affiliateUrl");
        Objects.requireNonNull(observedAt, "observedAt must not be null");

        List<Money> prices = Stream.of(regularPrice, currentPrice, salePrice)
                .filter(Objects::nonNull)
                .toList();
        String currency = prices.isEmpty() ? null : prices.getFirst().currency();
        if (currency != null && prices.stream().anyMatch(price -> !currency.equals(price.currency()))) {
            throw new IllegalArgumentException("offer prices must use the same currency");
        }
    }
}
