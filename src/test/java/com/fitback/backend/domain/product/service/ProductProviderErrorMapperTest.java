package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class ProductProviderErrorMapperTest {

    @Test
    void mapsRateLimitAndQuotaFailures() {
        assertMapped(
                ProductProviderFailure.RATE_LIMITED,
                ErrorCode.PRODUCT_PROVIDER_QUOTA_EXCEEDED
        );
        assertMapped(
                ProductProviderFailure.QUOTA_EXCEEDED,
                ErrorCode.PRODUCT_PROVIDER_QUOTA_EXCEEDED
        );
    }

    @Test
    void mapsMalformedProviderResponse() {
        assertMapped(
                ProductProviderFailure.MALFORMED_RESPONSE,
                ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID
        );
    }

    @Test
    void mapsUnavailableProviderFailures() {
        assertMapped(
                ProductProviderFailure.TIMEOUT,
                ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE
        );
        assertMapped(
                ProductProviderFailure.AUTHENTICATION_FAILED,
                ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE
        );
        assertMapped(
                ProductProviderFailure.UNAVAILABLE,
                ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE
        );
    }

    private static void assertMapped(
            ProductProviderFailure failure,
            ErrorCode expectedErrorCode
    ) {
        assertThat(ProductProviderErrorMapper.toBusinessException(
                new ProductProviderException("fixture", failure)
        ).getErrorCode()).isEqualTo(expectedErrorCode);
    }
}
