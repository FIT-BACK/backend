package com.fitback.backend.domain.product.dto;

import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
import java.util.List;

public record ProductDetailResponse(
        Long productId,
        String imageUrl,
        String name,
        String brandName,
        String sellerName,
        ProductCategory category,
        ProductPriceResponse price,
        String purchaseUrl,
        String affiliateUrl,
        ProductAvailability availability,
        ProductDataStatus dataStatus,
        List<String> tags,
        boolean isSaved
) {
}
