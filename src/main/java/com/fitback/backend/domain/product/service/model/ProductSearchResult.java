package com.fitback.backend.domain.product.service.model;

import java.util.List;

public record ProductSearchResult(List<ExternalProductCandidate> items, String nextCursor) {

    public ProductSearchResult {
        items = List.copyOf(items);
        nextCursor = ModelValidation.validateNullableText(nextCursor, "nextCursor");
    }

    public boolean hasNext() {
        return nextCursor != null;
    }
}
