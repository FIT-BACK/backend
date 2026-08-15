package com.fitback.backend.domain.product.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductSearchQueryTest {

    @Test
    void allowsCategoryOnlyQueryWithEmptyKeyword() {
        ProductSearchQuery query = new ProductSearchQuery(
                "  ",
                ProductCategory.DRESS,
                null,
                20
        );

        assertThat(query.keyword()).isEmpty();
        assertThat(query.category()).isEqualTo(ProductCategory.DRESS);
        assertThat(query.pageSize()).isEqualTo(20);
    }

    @Test
    void rejectsEmptyKeywordWithoutCategory() {
        assertThatThrownBy(() -> new ProductSearchQuery("", null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("keyword must not be blank when category is null");
    }
}
