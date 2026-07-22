package com.fitback.backend.domain.product.service.exception;

public enum ProductProviderFailure {
    TIMEOUT,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    AUTHENTICATION_FAILED,
    UNAVAILABLE,
    MALFORMED_RESPONSE
}
