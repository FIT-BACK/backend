package com.fitback.backend.external.shopping.config;

import java.net.URI;
import java.time.Duration;
import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shopping")
public record ShoppingProviderProperties(
        Provider provider,
        Shopify shopify,
        CandidateToken candidateToken
) {

    public ShoppingProviderProperties {
        provider = provider == null ? Provider.FIXTURE : provider;
        shopify = shopify == null ? Shopify.defaults(false) : shopify;
        candidateToken = candidateToken == null
                ? new CandidateToken(Duration.ofMinutes(10))
                : candidateToken;

        if (provider == Provider.SHOPIFY && !shopify.enabled()) {
            throw new IllegalArgumentException(
                    "shopping.shopify.enabled must be true when shopping.provider is shopify"
            );
        }
        if (provider != Provider.SHOPIFY && shopify.enabled()) {
            throw new IllegalArgumentException(
                    "shopping.shopify.enabled requires shopping.provider=shopify"
            );
        }
    }

    public enum Provider {
        FIXTURE,
        SHOPIFY
    }

    public record Shopify(
            boolean enabled,
            URI endpoint,
            URI agentProfile,
            Duration connectTimeout,
            Duration readTimeout,
            Duration snapshotTtl,
            String addressCountry,
            String language,
            String currency
    ) {

        private static final URI DEFAULT_ENDPOINT =
                URI.create("https://catalog.shopify.com/api/ucp/mcp");
        private static final URI DEFAULT_AGENT_PROFILE = URI.create(
                "https://shopify.dev/ucp/agent-profiles/2026-04-08/"
                        + "valid-with-capabilities.json"
        );

        public static Shopify defaults(boolean enabled) {
            return new Shopify(
                    enabled,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        public Shopify {
            endpoint = endpoint == null ? DEFAULT_ENDPOINT : requireHttpUri(endpoint, "endpoint");
            agentProfile = agentProfile == null
                    ? DEFAULT_AGENT_PROFILE
                    : requireHttpUri(agentProfile, "agent-profile");
            connectTimeout = positiveOrDefault(
                    connectTimeout,
                    Duration.ofSeconds(3),
                    "connect-timeout"
            );
            readTimeout = positiveOrDefault(
                    readTimeout,
                    Duration.ofSeconds(10),
                    "read-timeout"
            );
            snapshotTtl = positiveOrDefault(
                    snapshotTtl,
                    Duration.ofMinutes(15),
                    "snapshot-ttl"
            );
            addressCountry = textOrDefault(addressCountry, "KR", "address-country");
            if (!addressCountry.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException(
                        "shopping.shopify.address-country must be ISO 3166-1 alpha-2"
                );
            }
            language = textOrDefault(language, "ko", "language");
            currency = textOrDefault(currency, "KRW", "currency");
            try {
                Currency.getInstance(currency);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "shopping.shopify.currency must be ISO 4217",
                        exception
                );
            }
        }

        private static URI requireHttpUri(URI value, String property) {
            if (!value.isAbsolute()
                    || (!"http".equalsIgnoreCase(value.getScheme())
                    && !"https".equalsIgnoreCase(value.getScheme()))) {
                throw new IllegalArgumentException(
                        "shopping.shopify." + property + " must be an absolute HTTP URI"
                );
            }
            return value;
        }

        private static Duration positiveOrDefault(
                Duration value,
                Duration defaultValue,
                String property
        ) {
            Duration resolved = value == null ? defaultValue : value;
            if (resolved.isNegative() || resolved.isZero()) {
                throw new IllegalArgumentException(
                        "shopping.shopify." + property + " must be positive"
                );
            }
            return resolved;
        }

        private static String textOrDefault(
                String value,
                String defaultValue,
                String property
        ) {
            String resolved = value == null ? defaultValue : value.trim();
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException(
                        "shopping.shopify." + property + " must not be blank"
                );
            }
            return resolved;
        }
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
