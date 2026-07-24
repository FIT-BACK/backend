package com.fitback.backend.external.shopping.http;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;

public final class ProductProviderHttpFailureTranslator {

    private ProductProviderHttpFailureTranslator() {
    }

    public static ProductProviderException timeout(String provider) {
        return failure(provider, ProductProviderFailure.TIMEOUT);
    }

    public static ProductProviderException malformedResponse(String provider) {
        return failure(provider, ProductProviderFailure.MALFORMED_RESPONSE);
    }

    public static ProductProviderException fromStatus(String provider, int statusCode) {
        if (statusCode < 400 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 400 and 599");
        }

        ProductProviderFailure failure = switch (statusCode) {
            case 401, 403 -> ProductProviderFailure.AUTHENTICATION_FAILED;
            case 429 -> ProductProviderFailure.RATE_LIMITED;
            default -> statusCode >= 500
                    ? ProductProviderFailure.UNAVAILABLE
                    : ProductProviderFailure.MALFORMED_RESPONSE;
        };
        return failure(provider, failure);
    }

    private static ProductProviderException failure(
            String provider,
            ProductProviderFailure failure
    ) {
        return new ProductProviderException(provider, failure);
    }
}
