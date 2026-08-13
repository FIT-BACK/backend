package com.fitback.backend.external.shopping.shopify;

import java.util.Objects;

public record ShopifyCatalogLookup(String productId, String variantId) {

    public ShopifyCatalogLookup {
        productId = requireNonBlank(productId, "productId");
        variantId = nullableNonBlank(variantId);
    }

    public String requestId() {
        return variantId == null ? productId : variantId;
    }

    private static String requireNonBlank(String value, String fieldName) {
        String normalized = nullableNonBlank(value);
        return Objects.requireNonNull(normalized, fieldName + " must not be blank");
    }

    private static String nullableNonBlank(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
