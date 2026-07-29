package com.fitback.backend.external.shopping.shopify;

import java.util.List;

record ShopifyCatalogPage(List<ShopifyCatalogItem> items, String nextCursor) {

    ShopifyCatalogPage {
        items = List.copyOf(items);
    }
}
