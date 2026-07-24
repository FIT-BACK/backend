package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;

public final class ProductProviderErrorMapper {

    private ProductProviderErrorMapper() {
    }

    public static BusinessException toBusinessException(ProductProviderException exception) {
        ProductProviderFailure failure = exception.getFailure();
        ErrorCode errorCode = switch (failure) {
            case RATE_LIMITED, QUOTA_EXCEEDED ->
                    ErrorCode.PRODUCT_PROVIDER_QUOTA_EXCEEDED;
            case MALFORMED_RESPONSE ->
                    ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID;
            case TIMEOUT, AUTHENTICATION_FAILED, UNAVAILABLE ->
                    ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE;
        };
        return new BusinessException(errorCode);
    }
}
