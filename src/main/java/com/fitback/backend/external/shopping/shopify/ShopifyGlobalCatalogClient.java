package com.fitback.backend.external.shopping.shopify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ShopifyGlobalCatalogClient {

    ShopifyCatalogPage search(String query, String cursor, int limit);

    Optional<ShopifyCatalogItem> lookup(String productId, String variantId);

    Map<ShopifyCatalogLookup, ShopifyCatalogItem> lookupBatch(
            List<ShopifyCatalogLookup> lookups
    );
}
