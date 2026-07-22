package com.fitback.backend.domain.product.service.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }

        BigDecimal normalized = amount.stripTrailingZeros();
        int scale = Math.max(normalized.scale(), 0);
        int integerDigits = normalized.precision() - normalized.scale();
        if (scale > 2 || integerDigits > 17) {
            throw new IllegalArgumentException("amount must fit DECIMAL(19,2)");
        }

        currency = ModelValidation.requireNonBlank(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an uppercase ISO 4217 code");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code", exception);
        }
    }
}
