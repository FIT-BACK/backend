package com.fitback.backend.domain.product.dto;

import com.fitback.backend.domain.product.service.model.ProductAvailability;

public record ProductReferenceResponse(
        Long productId,
        boolean created,
        ProductAvailability availability
) {
}
