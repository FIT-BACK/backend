package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Map;

public record ProductDetailBatchResult(
        Map<Long, ProductDetailResponse> detailsByProductId,
        Map<Long, ErrorCode> failuresByProductId
) {

    public ProductDetailBatchResult {
        detailsByProductId = Map.copyOf(detailsByProductId);
        failuresByProductId = Map.copyOf(failuresByProductId);
    }

    public static ProductDetailBatchResult empty() {
        return new ProductDetailBatchResult(Map.of(), Map.of());
    }
}
