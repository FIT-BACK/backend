package com.fitback.backend.domain.product.dto;

import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import java.util.List;

public record ProductSearchResponse(
        List<Item> items,
        String nextCursor,
        boolean hasNext,
        int pageSize,
        boolean partial,
        List<String> warnings
) {

    public record Item(
            Long productId,
            String candidateToken,
            String imageUrl,
            String name,
            String brandName,
            String sellerName,
            ProductCategory category,
            ProductPriceResponse price,
            ProductAvailability availability,
            boolean detailSupported,
            boolean saveSupported
    ) {
    }
}
