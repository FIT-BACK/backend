package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.external.shopping.fixture.FixtureProductCategoryMapper;
import com.fitback.backend.external.shopping.fixture.FixtureShoppingProviderAdapter;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogAdapter;
import com.fitback.backend.external.shopping.shopify.ShopifyProductCategoryMapper;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProductCandidateMapperTest {

    private final ProductCandidateMapper mapper =
            new ProductCandidateMapper(new FixtureProductCategoryMapper());

    @Test
    void rejectsLookupResultWhoseIdentityDiffersFromTheRequestedProduct() {
        ExternalProductCandidate candidate = new FixtureShoppingProviderAdapter()
                .search(new ProductSearchQuery("Minimal", null, null, 10))
                .items()
                .getFirst();
        ProviderProductRef requestedRef = ProviderProductRef.stable(
                "fixture",
                "different-product",
                null,
                "fixture-store"
        );

        assertThatThrownBy(() -> mapper.snapshot(
                requestedRef,
                candidate,
                Instant.parse("2026-07-24T01:00:00Z")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID)
        );
    }

    @Test
    void retriesWithProductNameWhenShopifyCategoryPathIsUnknownOrOther() {
        ProductCandidateMapper shopifyMapper =
                new ProductCandidateMapper(new ShopifyProductCategoryMapper());

        assertThat(shopifyMapper.category(candidate("알 수 없는 분류", "립케이지 와이드핏 진")))
                .isEqualTo(ProductCategory.BOTTOM);
        assertThat(shopifyMapper.category(candidate("OTHER", "셔츠")))
                .isEqualTo(ProductCategory.TOP);
    }

    @Test
    void preservesRecognizedShopifyCategoryPathBeforeProductName() {
        ProductCandidateMapper shopifyMapper =
                new ProductCandidateMapper(new ShopifyProductCategoryMapper());

        assertThat(shopifyMapper.category(candidate("BOTTOM", "셔츠")))
                .isEqualTo(ProductCategory.BOTTOM);
    }

    private static ExternalProductCandidate candidate(String categoryPath, String name) {
        return new ExternalProductCandidate(
                ProviderProductRef.stable(
                        ShopifyGlobalCatalogAdapter.PROVIDER,
                        "product-1",
                        "variant-1",
                        "merchant-1"
                ),
                name,
                null,
                categoryPath,
                null,
                null,
                null,
                Instant.parse("2026-08-02T00:00:00Z")
        );
    }
}
