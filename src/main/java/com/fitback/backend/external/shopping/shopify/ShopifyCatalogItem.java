package com.fitback.backend.external.shopping.shopify;

import java.math.BigDecimal;

record ShopifyCatalogItem(
        String productId,
        String variantId,
        String title,
        String categoryPath,
        String imageUrl,
        BigDecimal price,
        String currency,
        Boolean available,
        String merchantId,
        String sellerName,
        String productUrl
) {
}
