package com.fitback.backend.external.shopping.shopify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ShopifyGlobalCatalogAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final ShopifyCatalogItem ITEM = new ShopifyCatalogItem(
            "gid://shopify/p/product-1",
            "gid://shopify/ProductVariant/variant-1",
            "Black Hoodie",
            "Apparel > Hoodies",
            "https://cdn.example/hoodie.jpg",
            new BigDecimal("73.00"),
            "USD",
            true,
            "gid://shopify/Shop/shop-1",
            "Example Shop",
            "https://merchant.example/products/hoodie"
    );

    @Test
    void mapsSearchResultsToStableProductCandidates() {
        AtomicReference<String> catalogQuery = new AtomicReference<>();
        ShopifyGlobalCatalogClient client = new StubClient() {
            @Override
            public ShopifyCatalogPage search(String query, String cursor, int limit) {
                catalogQuery.set(query);
                return new ShopifyCatalogPage(List.of(ITEM), "next-cursor");
            }
        };
        ShopifyGlobalCatalogAdapter adapter = adapter(client);

        var result = adapter.search(new ProductSearchQuery(
                "black hoodie",
                ProductCategory.TOP,
                null,
                10
        ));

        assertThat(catalogQuery.get()).isEqualTo("black hoodie shirt top");
        assertThat(result.nextCursor()).isEqualTo("next-cursor");
        assertThat(result.items()).singleElement().satisfies(candidate -> {
            assertThat(candidate.providerRef()).isEqualTo(providerRef());
            assertThat(candidate.offer().currentPrice().amount())
                    .isEqualByComparingTo("73.00");
            assertThat(candidate.offer().currentPrice().currency()).isEqualTo("USD");
            assertThat(candidate.offer().availability())
                    .isEqualTo(ProductAvailability.AVAILABLE);
            assertThat(candidate.categoryPath()).isEqualTo("Apparel > Hoodies");
            assertThat(candidate.observedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void lookupPreservesTheCandidateProviderIdentity() {
        ShopifyGlobalCatalogAdapter adapter = adapter(new StubClient());

        assertThat(adapter.lookup(providerRef()))
                .get()
                .satisfies(candidate -> {
                    assertThat(candidate.providerRef()).isEqualTo(providerRef());
                    assertThat(candidate.name()).isEqualTo("Black Hoodie");
                });
    }

    @Test
    void exposesIdentityOnlyPersistenceCapabilities() {
        ShopifyGlobalCatalogAdapter adapter = adapter(new StubClient());

        assertThat(adapter.capabilities().canPersistResult()).isTrue();
        assertThat(adapter.capabilities().canPersistPrice()).isFalse();
        assertThat(adapter.capabilities().canPersistImageUrl()).isFalse();
        assertThat(adapter.capabilities().requiresLiveLookup()).isTrue();
        assertThat(adapter.capabilities().maxTtl()).isNull();
    }

    private static ShopifyGlobalCatalogAdapter adapter(ShopifyGlobalCatalogClient client) {
        return new ShopifyGlobalCatalogAdapter(
                client,
                new ShopifyProductCategoryMapper(),
                ShoppingProviderProperties.Shopify.defaults(true),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static ProviderProductRef providerRef() {
        return ProviderProductRef.stable(
                ShopifyGlobalCatalogAdapter.PROVIDER,
                ITEM.productId(),
                ITEM.variantId(),
                ITEM.merchantId()
        );
    }

    private static class StubClient implements ShopifyGlobalCatalogClient {

        @Override
        public ShopifyCatalogPage search(String query, String cursor, int limit) {
            return new ShopifyCatalogPage(List.of(ITEM), null);
        }

        @Override
        public Optional<ShopifyCatalogItem> lookup(String productId, String variantId) {
            return Optional.of(ITEM);
        }
    }
}
