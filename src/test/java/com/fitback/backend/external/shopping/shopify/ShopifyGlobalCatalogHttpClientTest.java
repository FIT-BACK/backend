package com.fitback.backend.external.shopping.shopify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
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
                      "url": null,
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

    private static final String BATCH_LOOKUP_RESPONSE = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {
                "structuredContent": {
                  "products": [
                    {
                      "id": "gid://shopify/p/product-2",
                      "title": "Second Hoodie",
                      "media": [{"type": "image", "url": "https://cdn.example/second.jpg"}],
                      "variants": [
                        {
                          "id": "gid://shopify/ProductVariant/variant-2",
                          "price": {"amount": 6200, "currency": "USD"},
                          "availability": {"available": true},
                          "seller": {"id": "gid://shopify/Shop/shop-2", "name": "Second Shop"},
                          "inputs": [
                            {"id": "gid://shopify/ProductVariant/variant-2", "match": "featured"}
                          ]
                        }
                      ]
                    },
                    {
                      "id": "gid://shopify/p/product-1",
                      "title": "First Hoodie",
                      "media": [{"type": "image", "url": "https://cdn.example/first.jpg"}],
                      "variants": [
                        {
                          "id": "gid://shopify/ProductVariant/variant-1",
                          "price": {"amount": 7300, "currency": "USD"},
                          "availability": {"available": true},
                          "seller": {"id": "gid://shopify/Shop/shop-1", "name": "First Shop"},
                          "inputs": [
                            {"id": "gid://shopify/ProductVariant/variant-1", "match": "featured"}
                          ]
                        }
                      ]
                    }
                  ],
                  "messages": [
                    {
                      "type": "info",
                      "code": "not_found",
                      "content": "gid://shopify/ProductVariant/missing"
                    }
                  ]
                }
              }
            }
            """;

    private static final String PRODUCT_LOOKUP_RESPONSE = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {
                "structuredContent": {
                  "products": [
                    {
                      "id": "gid://shopify/p/product-1",
                      "title": "Product Lookup",
                      "variants": [
                        {
                          "id": "gid://shopify/ProductVariant/first",
                          "price": {"amount": 5100, "currency": "USD"},
                          "availability": {"available": true},
                          "seller": {"id": "gid://shopify/Shop/shop-1", "name": "First Shop"}
                        },
                        {
                          "id": "gid://shopify/ProductVariant/featured",
                          "price": {"amount": 6200, "currency": "USD"},
                          "availability": {"available": true},
                          "seller": {"id": "gid://shopify/Shop/shop-1", "name": "Featured Shop"},
                          "inputs": [
                            {"id": "gid://shopify/p/product-1", "match": "featured"}
                          ]
                        }
                      ]
                    }
                  ]
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
                    .isEqualTo("https://merchant.example/cart/variant-1:1");
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
    void batchLookupMapsReorderedProductsByVariantInputAndLeavesMissingLookupAbsent()
            throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) -> {
                    requestBody.set(body);
                    return new ShopifyGlobalCatalogHttpClient.TransportResponse(
                            200,
                            BATCH_LOOKUP_RESPONSE
                    );
                }
        );
        ShopifyCatalogLookup first = new ShopifyCatalogLookup(
                "gid://shopify/p/product-1",
                "gid://shopify/ProductVariant/variant-1"
        );
        ShopifyCatalogLookup second = new ShopifyCatalogLookup(
                "gid://shopify/p/product-2",
                "gid://shopify/ProductVariant/variant-2"
        );
        ShopifyCatalogLookup missing = new ShopifyCatalogLookup(
                "gid://shopify/p/missing",
                "gid://shopify/ProductVariant/missing"
        );

        var result = client.lookupBatch(List.of(first, second, missing));

        assertThat(result).containsOnlyKeys(first, second);
        assertThat(result.get(first).title()).isEqualTo("First Hoodie");
        assertThat(result.get(second).title()).isEqualTo("Second Hoodie");
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertThat(request.at("/params/name").asText()).isEqualTo("lookup_catalog");
        assertThat(request.at("/params/arguments/catalog/ids").size()).isEqualTo(3);
        assertThat(request.at("/params/arguments/catalog/ids/0").asText())
                .isEqualTo(first.requestId());
        assertThat(request.at("/params/arguments/catalog/ids/1").asText())
                .isEqualTo(second.requestId());
        assertThat(request.at("/params/arguments/catalog/ids/2").asText())
                .isEqualTo(missing.requestId());
    }

    @Test
    void batchLookupDoesNotCallShopifyForEmptyInputsAndRejectsMoreThanFifty() {
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) -> {
                    throw new AssertionError("empty batch must not call Shopify");
                }
        );

        assertThat(client.lookupBatch(List.of())).isEmpty();
        List<ShopifyCatalogLookup> tooMany = IntStream.range(0, 51)
                .mapToObj(index -> new ShopifyCatalogLookup(
                        "gid://shopify/p/product-" + index,
                        "gid://shopify/ProductVariant/variant-" + index
                ))
                .toList();

        assertThatThrownBy(() -> client.lookupBatch(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
    }

    @Test
    void batchLookupPreservesExistingFirstVariantSemanticsForProductIdentifier() {
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) -> new ShopifyGlobalCatalogHttpClient.TransportResponse(
                        200,
                        PRODUCT_LOOKUP_RESPONSE
                )
        );
        ShopifyCatalogLookup lookup = new ShopifyCatalogLookup(
                "gid://shopify/p/product-1",
                null
        );

        var result = client.lookupBatch(List.of(lookup));

        assertThat(result.get(lookup).variantId())
                .isEqualTo("gid://shopify/ProductVariant/first");
        assertThat(result.get(lookup).sellerName()).isEqualTo("First Shop");
    }

    @Test
    void convertsThreeFractionDigitCurrencyMinorUnits() {
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) ->
                        new ShopifyGlobalCatalogHttpClient.TransportResponse(
                                200,
                                SEARCH_RESPONSE
                                        .replace("\"amount\": 7300", "\"amount\": 1234")
                                        .replace("\"currency\": \"USD\"", "\"currency\": \"KWD\"")
                        )
        );

        assertThat(client.search("black hoodie", null, 1).items())
                .singleElement()
                .extracting(ShopifyCatalogItem::price)
                .isEqualTo(new BigDecimal("1.234"));
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
    void translatesHttpRateLimitForBatchLookup() {
        ShopifyGlobalCatalogHttpClient client = new ShopifyGlobalCatalogHttpClient(
                ShoppingProviderProperties.Shopify.defaults(true),
                objectMapper,
                (endpoint, timeout, body) ->
                        new ShopifyGlobalCatalogHttpClient.TransportResponse(429, "{}")
        );

        assertThatThrownBy(() -> client.lookupBatch(List.of(new ShopifyCatalogLookup(
                "gid://shopify/p/product-1",
                "gid://shopify/ProductVariant/variant-1"
        )))).isInstanceOfSatisfying(ProductProviderException.class, exception ->
                assertThat(exception.getFailure()).isEqualTo(ProductProviderFailure.RATE_LIMITED)
        );
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
