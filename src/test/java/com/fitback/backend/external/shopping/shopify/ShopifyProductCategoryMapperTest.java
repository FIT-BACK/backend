package com.fitback.backend.external.shopping.shopify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import org.junit.jupiter.api.Test;

class ShopifyProductCategoryMapperTest {

    private final ShopifyProductCategoryMapper mapper = new ShopifyProductCategoryMapper();

    @Test
    void mapsKoreanCategoryPathKeywords() {
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "하의 > 청바지"))
                .contains(ProductCategory.BOTTOM);
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "상의 > 니트"))
                .contains(ProductCategory.TOP);
    }

    @Test
    void returnsEmptyWhenNoKeywordMatches() {
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "무관한 카테고리 텍스트"))
                .isEmpty();
    }

    @Test
    void returnsEmptyForBlankOrWrongProvider() {
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, null)).isEmpty();
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, " ")).isEmpty();
        assertThat(mapper.map("fixture", "pants")).isEmpty();
    }

    @Test
    void mapsRealWorldKoreanProductNameUsedAsFallbackSignal() {
        // 실제 운영 서버에서 재현된 케이스: Shopify 응답에 뚜렷한 카테고리가
        // 없을 때 상품명("...와이드핏 진")만으로도 BOTTOM으로 잡혀야 한다.
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "립케이지 와이드핏 진"))
                .contains(ProductCategory.BOTTOM);
    }

    @Test
    void doesNotMatchAmbiguousKeywordsInsideUnrelatedWords() {
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "진주 목걸이"))
                .contains(ProductCategory.ACCESSORY);
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "laptop sleeve"))
                .isEmpty();
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "address label holder"))
                .isEmpty();
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "wireless router case"))
                .isEmpty();
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "wheel cover"))
                .isEmpty();
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "socket organizer"))
                .isEmpty();
    }

    @Test
    void mapsStandaloneShortEnglishKeywords() {
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "graphic tee"))
                .contains(ProductCategory.TOP);
        assertThat(mapper.map(ShopifyGlobalCatalogAdapter.PROVIDER, "baseball cap"))
                .contains(ProductCategory.ACCESSORY);
    }
}
