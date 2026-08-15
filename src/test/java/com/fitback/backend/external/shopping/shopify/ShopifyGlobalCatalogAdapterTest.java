package com.fitback.backend.external.shopping.shopify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.ProductCandidateMapper;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    void usesOnlyCategoryAnchorForCategoryOnlySearch() {
        AtomicReference<String> catalogQuery = new AtomicReference<>();
        ShopifyGlobalCatalogAdapter adapter = adapter(new StubClient() {
            @Override
            public ShopifyCatalogPage search(String query, String cursor, int limit) {
                catalogQuery.set(query);
                return new ShopifyCatalogPage(List.of(), null);
            }
        });

        adapter.search(new ProductSearchQuery(
                "",
                ProductCategory.DRESS,
                null,
                20
        ));

        assertThat(catalogQuery.get()).isEqualTo("dress");
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "TOP, Shirt, TOP, true",
                "OUTER, Jacket, OUTER, false",
                "NULL, Wool Coat, OUTER, false",
                "NULL, Oversized Shirt, TOP, true"
            },
            nullValues = "NULL"
    )
    void filtersByProviderCategoryOrProductNameWithoutCopyingRequestedCategory(
            String categoryPath,
            String title,
            ProductCategory expectedCategory,
            boolean includedForTop
    ) {
        ShopifyCatalogItem item = item(title, categoryPath);
        ShopifyGlobalCatalogAdapter adapter = adapter(new StubClient() {
            @Override
            public ShopifyCatalogPage search(String query, String cursor, int limit) {
                return new ShopifyCatalogPage(List.of(item), null);
            }
        });
        ProductCandidateMapper candidateMapper = new ProductCandidateMapper(
                new ShopifyProductCategoryMapper()
        );

        var candidates = adapter.search(new ProductSearchQuery(
                "shirt",
                ProductCategory.TOP,
                null,
                10
        )).items();
        var candidate = candidates.getFirst();
        ProductCategory actualCategory = candidateMapper.category(candidate);
        var includedCandidates = candidates.stream()
                .filter(current -> candidateMapper.category(current) == ProductCategory.TOP)
                .toList();

        assertThat(candidate.categoryPath()).isEqualTo(categoryPath);
        assertThat(actualCategory).isEqualTo(expectedCategory);
        assertThat(includedCandidates).hasSize(includedForTop ? 1 : 0);
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
    void batchLookupDeduplicatesReferencesAndLeavesPartialResultsAbsent() {
        AtomicReference<List<ShopifyCatalogLookup>> requestedLookups = new AtomicReference<>();
        ProviderProductRef first = providerRef();
        ProviderProductRef second = ProviderProductRef.stable(
                ShopifyGlobalCatalogAdapter.PROVIDER,
                "gid://shopify/p/product-2",
                "gid://shopify/ProductVariant/variant-2",
                "gid://shopify/Shop/shop-2"
        );
        ShopifyGlobalCatalogAdapter adapter = adapter(new StubClient() {
            @Override
            public Map<ShopifyCatalogLookup, ShopifyCatalogItem> lookupBatch(
                    List<ShopifyCatalogLookup> lookups
            ) {
                requestedLookups.set(List.copyOf(lookups));
                return Map.of(lookups.getFirst(), ITEM);
            }
        });

        var result = adapter.lookupBatch(List.of(first, second, first));

        assertThat(requestedLookups.get()).containsExactly(
                new ShopifyCatalogLookup(first.externalProductId(), first.externalVariantId()),
                new ShopifyCatalogLookup(second.externalProductId(), second.externalVariantId())
        );
        assertThat(result).containsOnlyKeys(first);
        assertThat(result.get(first).providerRef()).isEqualTo(first);
        assertThat(result.get(first).offer().seller()).isEqualTo("Example Shop");
        assertThat(result.get(first).offer().currentPrice().amount())
                .isEqualByComparingTo("73.00");
        assertThat(result.get(first).offer().purchaseUrl())
                .hasToString("https://merchant.example/products/hoodie");
        assertThat(result.get(first).imageUrl())
                .hasToString("https://cdn.example/hoodie.jpg");
    }

    @Test
    void batchLookupPropagatesProviderFailureUnchanged() {
        ProductProviderException failure = new ProductProviderException(
                "shopify",
                ProductProviderFailure.RATE_LIMITED
        );
        ShopifyGlobalCatalogAdapter adapter = adapter(new StubClient() {
            @Override
            public Map<ShopifyCatalogLookup, ShopifyCatalogItem> lookupBatch(
                    List<ShopifyCatalogLookup> lookups
            ) {
                throw failure;
            }
        });

        assertThatThrownBy(() -> adapter.lookupBatch(List.of(providerRef())))
                .isSameAs(failure);
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

    private static ShopifyCatalogItem item(String title, String categoryPath) {
        return new ShopifyCatalogItem(
                ITEM.productId(),
                ITEM.variantId(),
                title,
                categoryPath,
                ITEM.imageUrl(),
                ITEM.price(),
                ITEM.currency(),
                ITEM.available(),
                ITEM.merchantId(),
                ITEM.sellerName(),
                ITEM.productUrl()
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

        @Override
        public Map<ShopifyCatalogLookup, ShopifyCatalogItem> lookupBatch(
                List<ShopifyCatalogLookup> lookups
        ) {
            Map<ShopifyCatalogLookup, ShopifyCatalogItem> items = new LinkedHashMap<>();
            for (ShopifyCatalogLookup lookup : lookups) {
                items.put(lookup, ITEM);
            }
            return items;
        }
    }
}
