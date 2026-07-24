package com.fitback.backend.external.shopping.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shopping")
public record ShoppingProviderProperties(
        Provider provider,
        Shopify shopify
) {

    public ShoppingProviderProperties {
        provider = provider == null ? Provider.FIXTURE : provider;
        shopify = shopify == null ? new Shopify(false) : shopify;

        if (shopify.enabled()) {
            throw new IllegalArgumentException(
                    "shopping.shopify.enabled must remain false while provider selection is pending"
            );
        }
    }

    public enum Provider {
        FIXTURE,
        SHOPIFY
    }

    public record Shopify(boolean enabled) {
    }
}
