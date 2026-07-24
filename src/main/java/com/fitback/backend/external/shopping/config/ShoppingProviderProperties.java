package com.fitback.backend.external.shopping.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shopping")
public record ShoppingProviderProperties(
        Provider provider,
        Shopify shopify,
        CandidateToken candidateToken
) {

    public ShoppingProviderProperties {
        provider = provider == null ? Provider.FIXTURE : provider;
        shopify = shopify == null ? new Shopify(false) : shopify;
        candidateToken = candidateToken == null
                ? new CandidateToken(Duration.ofMinutes(10))
                : candidateToken;

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

    public record CandidateToken(Duration ttl) {

        public CandidateToken {
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException(
                        "shopping.candidate-token.ttl must be positive"
                );
            }
        }
    }
}
