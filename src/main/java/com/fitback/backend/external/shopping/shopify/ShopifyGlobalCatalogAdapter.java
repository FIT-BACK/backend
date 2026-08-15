package com.fitback.backend.external.shopping.shopify;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.Money;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductOffer;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.BatchProductCatalogPort;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ShopifyGlobalCatalogAdapter
        implements ProductCatalogPort, BatchProductCatalogPort {

    public static final String PROVIDER = "shopify";

    private final ShopifyGlobalCatalogClient client;
    private final ShopifyProductCategoryMapper categoryMapper;
    private final ProviderCapabilities capabilities;
    private final Clock clock;

    public ShopifyGlobalCatalogAdapter(
            ShopifyGlobalCatalogClient client,
            ShopifyProductCategoryMapper categoryMapper,
            ShoppingProviderProperties.Shopify properties,
            Clock clock
    ) {
        this.client = client;
        this.categoryMapper = categoryMapper;
        this.clock = clock;
        this.capabilities = new ProviderCapabilities(
                PROVIDER,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                null,
                true
        );
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public ProductSearchResult search(ProductSearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        ShopifyCatalogPage page = client.search(
                catalogQuery(query),
                query.cursor(),
                query.pageSize()
        );
        List<ExternalProductCandidate> items = page.items().stream()
                .map(item -> candidate(
                        ProviderProductRef.stable(
                                PROVIDER,
                                item.productId(),
                                item.variantId(),
                                item.merchantId()
                        ),
                        item,
                        item.categoryPath()
                ))
                .toList();
        return new ProductSearchResult(items, page.nextCursor());
    }

    @Override
    public Optional<ExternalProductCandidate> lookup(ProviderProductRef providerRef) {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        if (!supportsLookup(providerRef)) {
            return Optional.empty();
        }

        return client.lookup(
                        providerRef.externalProductId(),
                        providerRef.externalVariantId()
                )
                .filter(item -> matches(providerRef, item))
                .map(item -> candidate(
                        providerRef,
                        item,
                        item.categoryPath()
                ));
    }

    @Override
    public int maxLookupBatchSize() {
        return ShopifyGlobalCatalogHttpClient.MAX_LOOKUP_BATCH_SIZE;
    }

    @Override
    public Map<ProviderProductRef, ExternalProductCandidate> lookupBatch(
            List<ProviderProductRef> providerRefs
    ) {
        Objects.requireNonNull(providerRefs, "providerRefs must not be null");
        LinkedHashMap<ProviderProductRef, ShopifyCatalogLookup> lookupsByRef =
                new LinkedHashMap<>();
        for (ProviderProductRef providerRef : providerRefs) {
            Objects.requireNonNull(providerRef, "providerRefs must not contain null");
            if (supportsLookup(providerRef)) {
                lookupsByRef.putIfAbsent(
                        providerRef,
                        new ShopifyCatalogLookup(
                                providerRef.externalProductId(),
                                providerRef.externalVariantId()
                        )
                );
            }
        }
        if (lookupsByRef.isEmpty()) {
            return Map.of();
        }

        Map<ShopifyCatalogLookup, ShopifyCatalogItem> itemsByLookup = client.lookupBatch(
                List.copyOf(lookupsByRef.values())
        );
        Map<ProviderProductRef, ExternalProductCandidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<ProviderProductRef, ShopifyCatalogLookup> entry
                : lookupsByRef.entrySet()) {
            ShopifyCatalogItem item = itemsByLookup.get(entry.getValue());
            if (item != null && matches(entry.getKey(), item)) {
                candidates.put(
                        entry.getKey(),
                        candidate(entry.getKey(), item, item.categoryPath())
                );
            }
        }
        return Map.copyOf(candidates);
    }

    private String catalogQuery(ProductSearchQuery query) {
        if (query.category() == null) {
            return query.keyword();
        }
        String categoryTerm = categoryMapper.searchTerm(query.category());
        return categoryTerm.isBlank()
                ? query.keyword()
                : query.keyword() + " " + categoryTerm;
    }

    private ExternalProductCandidate candidate(
            ProviderProductRef providerRef,
            ShopifyCatalogItem item,
            String categoryPath
    ) {
        Instant observedAt = clock.instant();
        Money currentPrice = money(item.price(), item.currency());
        ProductOffer offer = new ProductOffer(
                null,
                currentPrice,
                null,
                availability(item.available()),
                item.sellerName(),
                uri(item.productUrl()),
                null,
                observedAt
        );
        return new ExternalProductCandidate(
                providerRef,
                item.title(),
                null,
                categoryPath,
                offer,
                uri(item.imageUrl()),
                null,
                observedAt
        );
    }

    private static boolean matches(
            ProviderProductRef expected,
            ShopifyCatalogItem actual
    ) {
        if (!expected.externalProductId().equals(actual.productId())) {
            return false;
        }
        if (expected.externalVariantId() != null
                && !expected.externalVariantId().equals(actual.variantId())) {
            return false;
        }
        return expected.merchantId() == null
                || expected.merchantId().equals(actual.merchantId());
    }

    private static boolean supportsLookup(ProviderProductRef providerRef) {
        return PROVIDER.equals(providerRef.provider())
                && providerRef.stable()
                && providerRef.externalProductId() != null;
    }

    private static Money money(BigDecimal amount, String currency) {
        return amount == null || currency == null ? null : new Money(amount, currency);
    }

    private static ProductAvailability availability(Boolean available) {
        if (available == null) {
            return ProductAvailability.UNKNOWN;
        }
        return available
                ? ProductAvailability.AVAILABLE
                : ProductAvailability.UNAVAILABLE;
    }

    private static URI uri(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                URI uri = URI.create(candidate);
                if (uri.isAbsolute()
                        && ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
                    return uri;
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore an invalid optional provider URL.
            }
        }
        return null;
    }

}
