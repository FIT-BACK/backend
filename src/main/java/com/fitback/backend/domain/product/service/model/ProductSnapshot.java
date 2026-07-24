package com.fitback.backend.domain.product.service.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductSnapshot(
        String name,
        String brandName,
        String sellerName,
        ProductCategory category,
        String imageUrl,
        BigDecimal listPrice,
        BigDecimal currentPrice,
        BigDecimal salePrice,
        String currency,
        Instant priceObservedAt,
        String purchaseUrl,
        String affiliateUrl,
        ProductAvailability availability,
        Instant snapshotExpiresAt
) {
}
