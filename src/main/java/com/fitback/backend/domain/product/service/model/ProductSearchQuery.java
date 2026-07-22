package com.fitback.backend.domain.product.service.model;

public record ProductSearchQuery(
        String keyword,
        ProductCategory category,
        String cursor,
        int pageSize
) {

    public ProductSearchQuery {
        keyword = ModelValidation.requireNonBlank(keyword, "keyword").trim();
        if (keyword.length() > 100) {
            throw new IllegalArgumentException("keyword must not exceed 100 characters");
        }
        cursor = ModelValidation.validateNullableText(cursor, "cursor");
        if (pageSize < 1 || pageSize > 20) {
            throw new IllegalArgumentException("pageSize must be between 1 and 20");
        }
    }
}
