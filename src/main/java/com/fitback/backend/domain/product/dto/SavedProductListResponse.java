package com.fitback.backend.domain.product.dto;

import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
import java.time.Instant;
import java.util.List;

public record SavedProductListResponse(
        List<Item> items,
        String nextCursor,
        boolean hasNext,
        int pageSize,
        boolean partial,
        List<String> warnings
) {

    public SavedProductListResponse {
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
    }

    public record Item(
            Long productId,
            String imageUrl,
            String name,
            String sellerName,
            ProductCategory category,
            ProductPriceResponse price,
            ProductAvailability availability,
            ProductDataStatus dataStatus,
            Instant savedAt
    ) {
    }
}
