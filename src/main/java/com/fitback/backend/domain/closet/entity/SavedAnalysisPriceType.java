package com.fitback.backend.domain.closet.entity;

import com.fitback.backend.domain.product.dto.ProductPriceResponse;

public enum SavedAnalysisPriceType {
    LIST,
    CURRENT,
    SALE;

    public ProductPriceResponse.Type toResponseType() {
        return ProductPriceResponse.Type.valueOf(name());
    }
}
