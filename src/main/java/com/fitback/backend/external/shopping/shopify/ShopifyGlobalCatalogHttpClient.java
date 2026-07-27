package com.fitback.backend.external.shopping.shopify;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.external.shopping.config.ShoppingProviderProperties;
import com.fitback.backend.external.shopping.http.ProductProviderHttpFailureTranslator;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ShopifyGlobalCatalogHttpClient implements ShopifyGlobalCatalogClient {

    static final String PROVIDER = "shopify";

    private final ShoppingProviderProperties.Shopify properties;
    private final ObjectMapper objectMapper;
    private final Transport transport;
    private final AtomicLong requestSequence = new AtomicLong();

    public ShopifyGlobalCatalogHttpClient(
            ShoppingProviderProperties.Shopify properties,
            ObjectMapper objectMapper
    ) {
        this(properties, objectMapper, new JdkTransport(properties.connectTimeout()));
    }

    ShopifyGlobalCatalogHttpClient(
            ShoppingProviderProperties.Shopify properties,
            ObjectMapper objectMapper,
            Transport transport
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    @Override
    public ShopifyCatalogPage search(String query, String cursor, int limit) {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("query", query);
        catalog.put("context", context());
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("limit", limit);
        if (cursor != null) {
            pagination.put("cursor", cursor);
        }
        catalog.put("pagination", pagination);

        JsonNode structuredContent = call("search_catalog", catalog);
        List<ShopifyCatalogItem> items = products(structuredContent, null);
        JsonNode paginationNode = structuredContent.path("pagination");
        String nextCursor = paginationNode.path("has_next_page").asBoolean(false)
                ? nullableText(paginationNode.path("cursor"))
                : null;
        return new ShopifyCatalogPage(items, nextCursor);
    }

    @Override
    public Optional<ShopifyCatalogItem> lookup(String productId, String variantId) {
        String lookupId = variantId == null ? productId : variantId;
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("ids", List.of(lookupId));
        catalog.put("context", context());

        List<ShopifyCatalogItem> items = products(
                call("lookup_catalog", catalog),
                variantId
        );
        return items.stream()
                .filter(item -> productId.equals(item.productId()))
                .filter(item -> variantId == null || variantId.equals(item.variantId()))
                .findFirst();
    }

    private JsonNode call(String toolName, Map<String, Object> catalog) {
        Map<String, Object> agent = Map.of("profile", properties.agentProfile().toString());
        Map<String, Object> meta = Map.of("ucp-agent", agent);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("meta", meta);
        arguments.put("catalog", catalog);
        Map<String, Object> params = Map.of(
                "name", toolName,
                "arguments", arguments
        );
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", requestSequence.incrementAndGet(),
                "params", params
        );

        TransportResponse response;
        try {
            response = transport.post(
                    properties.endpoint(),
                    properties.readTimeout(),
                    objectMapper.writeValueAsString(request)
            );
        } catch (HttpTimeoutException exception) {
            throw ProductProviderHttpFailureTranslator.timeout(PROVIDER);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProductProviderException(PROVIDER, ProductProviderFailure.UNAVAILABLE);
        } catch (IOException exception) {
            throw new ProductProviderException(PROVIDER, ProductProviderFailure.UNAVAILABLE);
        } catch (Exception exception) {
            throw ProductProviderHttpFailureTranslator.malformedResponse(PROVIDER);
        }

        if (response.statusCode() >= 400) {
            throw ProductProviderHttpFailureTranslator.fromStatus(
                    PROVIDER,
                    response.statusCode()
            );
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception exception) {
            throw ProductProviderHttpFailureTranslator.malformedResponse(PROVIDER);
        }

        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw jsonRpcFailure(error);
        }
        JsonNode result = root.path("result");
        if (result.path("isError").asBoolean(false)) {
            throw ProductProviderHttpFailureTranslator.malformedResponse(PROVIDER);
        }
        JsonNode structuredContent = result.path("structuredContent");
        if (!structuredContent.isObject()) {
            throw ProductProviderHttpFailureTranslator.malformedResponse(PROVIDER);
        }
        return structuredContent;
    }

    private Map<String, Object> context() {
        return Map.of(
                "address_country", properties.addressCountry(),
                "language", properties.language(),
                "currency", properties.currency()
        );
    }

    private List<ShopifyCatalogItem> products(
            JsonNode structuredContent,
            String requestedVariantId
    ) {
        List<ShopifyCatalogItem> products = new ArrayList<>();
        JsonNode productArray = structuredContent.path("products");
        if (productArray.isArray()) {
            for (JsonNode product : productArray) {
                parseProduct(product, requestedVariantId).ifPresent(products::add);
            }
            return products;
        }

        JsonNode product = structuredContent.path("product");
        if (product.isObject()) {
            parseProduct(product, requestedVariantId).ifPresent(products::add);
            return products;
        }
        throw ProductProviderHttpFailureTranslator.malformedResponse(PROVIDER);
    }

    private Optional<ShopifyCatalogItem> parseProduct(
            JsonNode product,
            String requestedVariantId
    ) {
        String productId = nullableText(product.path("id"));
        String title = nullableText(product.path("title"));
        if (productId == null || title == null) {
            return Optional.empty();
        }

        JsonNode variant = selectVariant(product.path("variants"), requestedVariantId);
        String variantId = nullableText(variant.path("id"));
        JsonNode seller = variant.path("seller");
        JsonNode price = variant.path("price");
        if (!price.isObject()) {
            price = product.path("price_range").path("min");
        }

        String currency = nullableText(price.path("currency"));
        BigDecimal amount = majorAmount(price.path("amount"), currency);
        Boolean available = variant.path("availability").has("available")
                ? variant.path("availability").path("available").asBoolean()
                : null;
        String imageUrl = firstImageUrl(variant.path("media"));
        if (imageUrl == null) {
            imageUrl = firstImageUrl(product.path("media"));
        }

        return Optional.of(new ShopifyCatalogItem(
                productId,
                variantId,
                title,
                categoryPath(product.path("categories")),
                imageUrl,
                amount,
                amount == null ? null : currency,
                available,
                nullableText(seller.path("id")),
                nullableText(seller.path("name")),
                firstNonBlank(
                        nullableText(product.path("url")),
                        nullableText(variant.path("url"))
                )
        ));
    }

    private static JsonNode selectVariant(JsonNode variants, String requestedVariantId) {
        if (!variants.isArray() || variants.isEmpty()) {
            return variants;
        }
        if (requestedVariantId != null) {
            for (JsonNode variant : variants) {
                if (requestedVariantId.equals(nullableText(variant.path("id")))) {
                    return variant;
                }
            }
        }
        return variants.get(0);
    }

    private static BigDecimal majorAmount(JsonNode amountNode, String currencyCode) {
        if (!amountNode.isNumber() || currencyCode == null) {
            return null;
        }
        try {
            int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            if (fractionDigits < 0 || fractionDigits > 2) {
                return null;
            }
            return amountNode.decimalValue().movePointLeft(fractionDigits);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String firstImageUrl(JsonNode media) {
        if (!media.isArray()) {
            return null;
        }
        for (JsonNode item : media) {
            if ("image".equals(nullableText(item.path("type")))) {
                return nullableText(item.path("url"));
            }
        }
        return null;
    }

    private static String categoryPath(JsonNode categories) {
        if (!categories.isArray()) {
            return null;
        }
        String fallback = null;
        for (JsonNode category : categories) {
            String value = nullableText(category.path("value"));
            if (value == null) {
                continue;
            }
            if ("merchant".equals(nullableText(category.path("taxonomy")))) {
                return value;
            }
            if (fallback == null) {
                fallback = value;
            }
        }
        return fallback;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String nullableText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static ProductProviderException jsonRpcFailure(JsonNode error) {
        String code = nullableText(error.path("data").path("code"));
        if (code == null) {
            code = nullableText(error.path("code"));
        }
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT);
        ProductProviderFailure failure;
        if (normalized.contains("rate") || normalized.contains("throttl")) {
            failure = ProductProviderFailure.RATE_LIMITED;
        } else if (normalized.contains("quota")) {
            failure = ProductProviderFailure.QUOTA_EXCEEDED;
        } else if (normalized.contains("auth") || normalized.contains("profile")) {
            failure = ProductProviderFailure.AUTHENTICATION_FAILED;
        } else {
            failure = ProductProviderFailure.MALFORMED_RESPONSE;
        }
        return new ProductProviderException(PROVIDER, failure);
    }

    @FunctionalInterface
    interface Transport {

        TransportResponse post(URI endpoint, Duration timeout, String body)
                throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, String body) {
    }

    private static final class JdkTransport implements Transport {

        private final HttpClient httpClient;

        private JdkTransport(Duration connectTimeout) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        @Override
        public TransportResponse post(URI endpoint, Duration timeout, String body)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            return new TransportResponse(response.statusCode(), response.body());
        }
    }
}
