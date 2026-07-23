package com.fitback.backend.domain.product.service.exception;

import java.util.Objects;

public class ProductProviderException extends RuntimeException {

    private final String provider;
    private final ProductProviderFailure failure;

    public ProductProviderException(String provider, ProductProviderFailure failure) {
        super("Product provider request failed: " + Objects.requireNonNull(failure, "failure must not be null"));
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        this.provider = provider;
        this.failure = failure;
    }

    public String getProvider() {
        return provider;
    }

    public ProductProviderFailure getFailure() {
        return failure;
    }
}
