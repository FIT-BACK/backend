package com.fitback.backend.external.shopping.shopify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ShopifyGlobalCatalogHttpClientTest {

    private static final String SEARCH_RESPONSE = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {
                "structuredContent": {
                  "products": [
                    {
                      "id": "gid://shopify/p/product-1",
                      "title": "Black Hoodie",
                      "url": "https://merchant.example/products/hoodie",
                      "categories": [
                        {
                          "value": "212",
                          "taxonomy": "google_product_category"
                        },
                        {
                          "value": "Apparel > Hoodies",
                          "taxonomy": "merchant"
                        }
                      ],
                      "media": [
                        {
                          "type": "image",
                          "url": "https://cdn.example/hoodie.jpg"
                        }
                      ],
                      "variants": [
                        {
                          "id": "gid://shopify/ProductVariant/variant-1",
                          "url": "https://merchant.example/products/hoodie",
                          "checkout_url": "https://merchant.example/cart/variant-1:1",
                          "price": {
                            "amount": 7300,
                            "currency": "USD"
                          },
                          "availability": {
                            "available": true
                          },
                          "seller": {
                            "id": "gid://shopify/Shop/shop-1",
                            "name": "Example Shop"
                          }
                        }
                      ]
                    }
                  ],
                  "pagination": {
                    "has_next_page": true,
                    "cursor": "next-cursor"
                  }
                }
              }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchesWithAgentProfileAndConvertsMinorCurrencyUnits() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) -> {
                    requestBody.set(body);
                    return new ShopifyGlobalCatalogHttpClient.TransportResponse(
                            200,
                            SEARCH_RESPONSE
                    );
                }
        );

        ShopifyCatalogPage result = client.search("black hoodie", "cursor-1", 1);

        assertThat(result.nextCursor()).isEqualTo("next-cursor");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo("gid://shopify/p/product-1");
            assertThat(item.variantId())
                    .isEqualTo("gid://shopify/ProductVariant/variant-1");
            assertThat(item.price()).isEqualByComparingTo("73.00");
            assertThat(item.currency()).isEqualTo("USD");
            assertThat(item.available()).isTrue();
            assertThat(item.categoryPath()).isEqualTo("Apparel > Hoodies");
            assertThat(item.productUrl())
                    .isEqualTo("https://merchant.example/products/hoodie");
        });

        JsonNode request = objectMapper.readTree(requestBody.get());
        assertThat(request.at("/params/name").asText()).isEqualTo("search_catalog");
        assertThat(request.at("/params/arguments/catalog/query").asText())
                .isEqualTo("black hoodie");
        assertThat(request.at("/params/arguments/catalog/pagination/cursor").asText())
                .isEqualTo("cursor-1");
        assertThat(request.at("/params/arguments/meta/ucp-agent/profile").asText())
                .startsWith("https://shopify.dev/");
    }

    @Test
    void looksUpTheExactVariantIdentifier() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) -> {
                    requestBody.set(body);
                    return new ShopifyGlobalCatalogHttpClient.TransportResponse(
                            200,
                            SEARCH_RESPONSE
                    );
                }
        );

        assertThat(client.lookup(
                "gid://shopify/p/product-1",
                "gid://shopify/ProductVariant/variant-1"
        )).isPresent();

        JsonNode request = objectMapper.readTree(requestBody.get());
        assertThat(request.at("/params/name").asText()).isEqualTo("lookup_catalog");
        assertThat(request.at("/params/arguments/catalog/ids/0").asText())
                .isEqualTo("gid://shopify/ProductVariant/variant-1");
    }

    @Test
    void translatesHttpRateLimitResponse() {
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) ->
                        new ShopifyGlobalCatalogHttpClient.TransportResponse(429, "{}")
        );

        assertThatThrownBy(() -> client.search("hoodie", null, 1))
                .isInstanceOfSatisfying(ProductProviderException.class, exception -> {
                    assertThat(exception.getProvider()).isEqualTo("shopify");
                    assertThat(exception.getFailure())
                            .isEqualTo(ProductProviderFailure.RATE_LIMITED);
                });
    }

    @Test
    void translatesTransportTimeout() {
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) -> {
                    throw new HttpTimeoutException("timed out");
                }
        );

        assertThatThrownBy(() -> client.search("hoodie", null, 1))
                .isInstanceOfSatisfying(ProductProviderException.class, exception ->
                        assertThat(exception.getFailure())
                                .isEqualTo(ProductProviderFailure.TIMEOUT)
                );
    }

    @Test
    void translatesJsonRpcQuotaFailure() {
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) ->
                        new ShopifyGlobalCatalogHttpClient.TransportResponse(
                                200,
                                """
                                {
                                  "jsonrpc": "2.0",
                                  "id": 1,
                                  "error": {
                                    "code": "quota_exceeded",
                                    "message": "Catalog quota exceeded"
                                  }
                                }
                                """
                        )
        );

        assertThatThrownBy(() -> client.search("hoodie", null, 1))
                .isInstanceOfSatisfying(ProductProviderException.class, exception ->
                        assertThat(exception.getFailure())
                                .isEqualTo(ProductProviderFailure.QUOTA_EXCEEDED)
                );
    }
}
