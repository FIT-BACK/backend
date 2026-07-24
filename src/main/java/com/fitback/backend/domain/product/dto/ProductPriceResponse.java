package com.fitback.backend.domain.product.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductPriceResponse(
        BigDecimal amount,
        String currency,
        Type type,
        Instant observedAt
) {

    public enum Type {
        LIST,
        CURRENT,
        SALE
    }
}
