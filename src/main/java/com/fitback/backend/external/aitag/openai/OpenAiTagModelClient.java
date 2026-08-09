package com.fitback.backend.external.aitag.openai;

import com.fitback.backend.external.aitag.AiTagImage;
import com.fitback.backend.external.aitag.AiTagModelClient;
import com.fitback.backend.external.aitag.AiTagModelOutput;
import com.fitback.backend.external.aitag.AiTagModelRequest;
import com.fitback.backend.external.aitag.AiTagModelResult;
import com.fitback.backend.external.aitag.AiTagResponseParser;
import com.fitback.backend.external.aitag.config.AiTagProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiTagModelClient implements AiTagModelClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final int MAX_LOGGED_TYPE_COUNT = 20;
    private static final int MAX_LOGGED_TYPE_LENGTH = 64;
    private static final int MAX_X_REQUEST_ID_LENGTH = 128;
    private static final String UNAVAILABLE_X_REQUEST_ID = "UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(OpenAiTagModelClient.class);

    private final AiTagProperties.OpenAi properties;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final AiTagResponseParser responseParser;
    private final Transport transport;

    public OpenAiTagModelClient(
            AiTagProperties.OpenAi properties,
            Duration requestTimeout,
            ObjectMapper objectMapper
    ) {
        this(properties, requestTimeout, objectMapper, new JdkTransport(requestTimeout));
    }

    OpenAiTagModelClient(
            AiTagProperties.OpenAi properties,
            Duration requestTimeout,
            ObjectMapper objectMapper,
            Transport transport
    ) {
        properties.validateForUse();
        this.properties = properties;
        this.requestTimeout = requestTimeout;
        this.objectMapper = objectMapper;
        this.responseParser = new AiTagResponseParser(objectMapper);
        this.transport = transport;
    }

    @Override
    public AiTagModelResult analyze(AiTagImage image, AiTagModelRequest request) {
        long startedAt = System.nanoTime();
        TransportResponse response;
        try {
            response = transport.post(
                    ENDPOINT,
                    properties.apiKey(),
                    requestTimeout,
                    objectMapper.writeValueAsString(payload(image, request))
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn(
                    "AI tag provider call interrupted. provider=openai model={} providerErrorCategory=INTERRUPTED "
                            + "elapsedMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            throw providerFailure(null, "INTERRUPTED", null, startedAt);
        } catch (HttpTimeoutException exception) {
            log.warn(
                    "AI tag provider call failed. provider=openai model={} providerErrorCategory=TIMEOUT "
                            + "elapsedMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            throw providerFailure(null, "TIMEOUT", null, startedAt);
        } catch (IOException exception) {
            log.warn(
                    "AI tag provider call failed. provider=openai model={} providerErrorCategory=TRANSPORT_ERROR "
                            + "elapsedMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            throw providerFailure(null, "TRANSPORT_ERROR", null, startedAt);
        } catch (RuntimeException exception) {
            log.warn(
                    "AI tag provider request failed. provider=openai model={} providerErrorCategory=REQUEST_ERROR "
                            + "elapsedMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            throw providerFailure(null, "REQUEST_ERROR", null, startedAt);
        }
        if (response.statusCode() >= 400) {
            ResponseMetadata metadata = responseMetadata(response.body());
            log.warn(
                    "AI tag provider returned an error. provider=openai model={} httpStatus={} "
                            + "responseStatus={} incompleteDetailsReason={} outputTypes={} contentTypes={} "
                            + "providerErrorCategory={} elapsedMillis={} xRequestId={}",
                    properties.model(),
                    response.statusCode(),
                    response.statusCode(),
                    metadata.incompleteDetailsReason(),
                    metadata.outputTypes(),
                    metadata.contentTypes(),
                    providerErrorCategory(response.statusCode()),
                    elapsedMillis(startedAt),
                    response.xRequestId()
            );
            throw providerFailure(
                    response.statusCode(), providerErrorCategory(response.statusCode()), null, startedAt,
                    response.xRequestId()
            );
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception exception) {
            logResponseParsingFailure(
                    response,
                    ResponseMetadata.unavailable(),
                    "INVALID_RESPONSE_JSON",
                    startedAt
            );
            throw providerFailure(
                    response.statusCode(), null, "INVALID_RESPONSE_JSON", startedAt, response.xRequestId()
            );
        }
        if (root == null || !root.isObject()) {
            logResponseParsingFailure(
                    response,
                    responseMetadata(root),
                    "INVALID_RESPONSE_SHAPE",
                    startedAt
            );
            throw providerFailure(
                    response.statusCode(), null, "INVALID_RESPONSE_SHAPE", startedAt, response.xRequestId()
            );
        }

        ResponseMetadata metadata = responseMetadata(root);
        String outputJson;
        try {
            outputJson = outputText(root);
        } catch (ResponseParsingException exception) {
            logResponseParsingFailure(response, metadata, exception.category(), startedAt);
            throw providerFailure(
                    response.statusCode(), null, exception.category(), startedAt, response.xRequestId()
            );
        }

        try {
            JsonNode modelOutputRoot = objectMapper.readTree(outputJson);
            if (modelOutputRoot == null) {
                throw new IllegalArgumentException("model output is empty");
            }
        } catch (Exception exception) {
            logResponseParsingFailure(
                    response,
                    metadata,
                    "INVALID_MODEL_OUTPUT_JSON",
                    startedAt
            );
            throw providerFailure(
                    response.statusCode(), null, "INVALID_MODEL_OUTPUT_JSON", startedAt, response.xRequestId()
            );
        }

        try {
            AiTagModelOutput output = responseParser.parse(outputJson);
            return new AiTagModelResult(
                    "openai",
                    properties.model(),
                    output.garments(),
                    nullableInt(root.path("usage").path("input_tokens")),
                    nullableInt(root.path("usage").path("output_tokens")),
                    elapsedMillis(startedAt),
                    response.xRequestId()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            logResponseParsingFailure(
                    response,
                    metadata,
                    "INVALID_MODEL_OUTPUT_SCHEMA:" + schemaFailureCategory(exception),
                    startedAt
            );
            throw providerFailure(
                    response.statusCode(), null,
                    "INVALID_MODEL_OUTPUT_SCHEMA:" + schemaFailureCategory(exception), startedAt,
                    response.xRequestId()
            );
        }
    }

    private Map<String, Object> payload(AiTagImage image, AiTagModelRequest request) {
        String dataUrl = "data:%s;base64,%s".formatted(
                image.contentType(),
                Base64.getEncoder().encodeToString(image.bytes())
        );
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", "fitback_tags");
        format.put("strict", true);
        format.put("schema", request.jsonSchema());
        return Map.of(
                "model", properties.model(),
                "store", false,
                "input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", request.prompt()),
                                Map.of(
                                        "type", "input_image",
                                        "image_url", dataUrl,
                                        "detail", "original"
                                )
                        )
                )),
                "reasoning", Map.of("effort", "none"),
                "text", Map.of("format", format)
        );
    }

    private static String outputText(JsonNode root) {
        JsonNode outputs = root.path("output");
        if (!outputs.isArray()) {
            throw new ResponseParsingException("MISSING_OUTPUT");
        }
        for (JsonNode output : outputs) {
            JsonNode contents = output.path("content");
            if (!contents.isArray()) {
                throw new ResponseParsingException("INVALID_RESPONSE_SHAPE");
            }
            for (JsonNode content : contents) {
                if ("output_text".equals(content.path("type").asText())) {
                    String text = content.path("text").asText();
                    if (text.isBlank()) {
                        throw new ResponseParsingException("EMPTY_OUTPUT_TEXT");
                    }
                    return text;
                }
            }
        }
        throw new ResponseParsingException("MISSING_OUTPUT_TEXT");
    }

    private ResponseMetadata responseMetadata(String responseBody) {
        try {
            return responseMetadata(objectMapper.readTree(responseBody));
        } catch (Exception exception) {
            return ResponseMetadata.unavailable();
        }
    }

    private static ResponseMetadata responseMetadata(JsonNode root) {
        if (root == null || !root.isObject()) {
            return ResponseMetadata.unavailable();
        }
        return new ResponseMetadata(
                safeToken(root.path("incomplete_details").path("reason")),
                typeNames(root.path("output")),
                contentTypeNames(root.path("output"))
        );
    }

    private static List<String> typeNames(JsonNode outputs) {
        List<String> types = new ArrayList<>();
        if (!outputs.isArray()) {
            return types;
        }
        for (JsonNode output : outputs) {
            addBoundedType(types, output.path("type"));
        }
        return List.copyOf(types);
    }

    private static List<String> contentTypeNames(JsonNode outputs) {
        List<String> types = new ArrayList<>();
        if (!outputs.isArray()) {
            return types;
        }
        for (JsonNode output : outputs) {
            JsonNode contents = output.path("content");
            if (!contents.isArray()) {
                continue;
            }
            for (JsonNode content : contents) {
                addBoundedType(types, content.path("type"));
            }
        }
        return List.copyOf(types);
    }

    private static void addBoundedType(List<String> types, JsonNode type) {
        if (types.size() < MAX_LOGGED_TYPE_COUNT) {
            types.add(safeToken(type));
        }
    }

    private static String safeToken(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "UNKNOWN";
        }
        if (!node.isTextual()) {
            return "<redacted>";
        }
        String value = node.asText();
        if (value == null || value.isBlank() || value.length() > MAX_LOGGED_TYPE_LENGTH) {
            return "UNKNOWN";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character == '_' || character == '-' || character == '.'
                    || character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9')) {
                return "<redacted>";
            }
        }
        return value;
    }

    private void logResponseParsingFailure(
            TransportResponse response,
            ResponseMetadata metadata,
            String category,
            long startedAt
    ) {
        log.warn(
                "AI tag provider response parsing failed. provider=openai model={} responseStatus={} "
                        + "incompleteDetailsReason={} outputTypes={} contentTypes={} "
                        + "responseParsingCategory={} elapsedMillis={} xRequestId={}",
                properties.model(),
                response.statusCode(),
                metadata.incompleteDetailsReason(),
                metadata.outputTypes(),
                metadata.contentTypes(),
                category,
                elapsedMillis(startedAt),
                response.xRequestId()
        );
    }

    private static Integer nullableInt(JsonNode node) {
        return node.isIntegralNumber() ? node.asInt() : null;
    }

    private static String schemaFailureCategory(Exception exception) {
        String message = exception.getMessage();
        if ("garment tags must not be empty".equals(message)) {
            return "EMPTY_GARMENT_TAGS";
        }
        if ("garment tag limit exceeded".equals(message)) {
            return "GARMENT_TAG_LIMIT_EXCEEDED";
        }
        if ("canonical garment tags must be unique".equals(message)) {
            return "DUPLICATE_CANONICAL_TAGS";
        }
        if ("garments must contain between 1 and 3 items".equals(message)) {
            return "GARMENT_COUNT_OUT_OF_RANGE";
        }
        if ("garment pieces must be unique".equals(message)) {
            return "DUPLICATE_GARMENT_PIECE";
        }
        if ("canonicalTags must be an array".equals(message)) {
            return "CANONICAL_TAGS_NOT_ARRAY";
        }
        if ("suggestedTags must be an array".equals(message)) {
            return "SUGGESTED_TAGS_NOT_ARRAY";
        }
        if ("garments must be an array".equals(message)) {
            return "GARMENTS_NOT_ARRAY";
        }
        if ("tag name must not be blank".equals(message)) {
            return "CANONICAL_TAG_NAME_BLANK";
        }
        if ("suggested tag name must not be blank".equals(message)) {
            return "SUGGESTED_TAG_NAME_BLANK";
        }
        if ("suggested tag confidence must be between 0 and 1".equals(message)) {
            return "SUGGESTED_TAG_CONFIDENCE_OUT_OF_RANGE";
        }
        if ("suggested tag evidence must not be blank".equals(message)) {
            return "SUGGESTED_TAG_EVIDENCE_BLANK";
        }
        if (message != null && message.startsWith("No enum constant "
                + "com.fitback.backend.external.aitag.GarmentPiece.")) {
            return "INVALID_GARMENT_PIECE";
        }
        if (message != null && message.startsWith("No enum constant "
                + "com.fitback.backend.domain.tag.entity.TagType.")) {
            return "INVALID_TAG_TYPE";
        }
        return "UNKNOWN";
    }

    private static String providerErrorCategory(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return "AUTHENTICATION";
        }
        if (statusCode == 408) {
            return "TIMEOUT";
        }
        if (statusCode == 429) {
            return "RATE_LIMIT";
        }
        if (statusCode >= 400 && statusCode < 500) {
            return "CLIENT_ERROR";
        }
        if (statusCode >= 500 && statusCode < 600) {
            return "SERVER_ERROR";
        }
        return "UNEXPECTED_STATUS";
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private record ResponseMetadata(
            String incompleteDetailsReason,
            List<String> outputTypes,
            List<String> contentTypes
    ) {

        private static ResponseMetadata unavailable() {
            return new ResponseMetadata("UNKNOWN", List.of(), List.of());
        }
    }

    private static final class ResponseParsingException extends RuntimeException {

        private final String category;

        private ResponseParsingException(String category) {
            this.category = category;
        }

        private String category() {
            return category;
        }
    }

    private static ProviderFailure providerFailure(
            Integer providerHttpStatus,
            String providerErrorCategory,
            String responseParsingCategory,
            long startedAt
    ) {
        return providerFailure(
                providerHttpStatus, providerErrorCategory, responseParsingCategory, startedAt,
                UNAVAILABLE_X_REQUEST_ID
        );
    }

    private static ProviderFailure providerFailure(
            Integer providerHttpStatus,
            String providerErrorCategory,
            String responseParsingCategory,
            long startedAt,
            String xRequestId
    ) {
        return new ProviderFailure(
                providerHttpStatus,
                providerErrorCategory,
                responseParsingCategory,
                elapsedMillis(startedAt),
                sanitizeXRequestId(xRequestId)
        );
    }

    public static final class ProviderFailure extends BusinessException {

        private final Integer providerHttpStatus;
        private final String providerErrorCategory;
        private final String responseParsingCategory;
        private final long elapsedMillis;
        private final String xRequestId;

        private ProviderFailure(
                Integer providerHttpStatus,
                String providerErrorCategory,
                String responseParsingCategory,
                long elapsedMillis,
                String xRequestId
        ) {
            super(ErrorCode.ANALYSIS_NOT_READY);
            this.providerHttpStatus = providerHttpStatus;
            this.providerErrorCategory = providerErrorCategory;
            this.responseParsingCategory = responseParsingCategory;
            this.elapsedMillis = elapsedMillis;
            this.xRequestId = xRequestId;
        }

        public Integer providerHttpStatus() {
            return providerHttpStatus;
        }

        public String providerErrorCategory() {
            return providerErrorCategory;
        }

        public String responseParsingCategory() {
            return responseParsingCategory;
        }

        public long elapsedMillis() {
            return elapsedMillis;
        }

        public String xRequestId() {
            return xRequestId;
        }
    }

    @FunctionalInterface
    interface Transport {
        TransportResponse post(String endpoint, String apiKey, Duration timeout, String body)
                throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, String body, String xRequestId) {
        TransportResponse(int statusCode, String body) {
            this(statusCode, body, UNAVAILABLE_X_REQUEST_ID);
        }

        TransportResponse {
            xRequestId = sanitizeXRequestId(xRequestId);
        }
    }

    static String sanitizeXRequestId(HttpHeaders headers) {
        return sanitizeXRequestId(headers == null ? null : headers.firstValue("x-request-id").orElse(null));
    }

    static String sanitizeXRequestId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_X_REQUEST_ID_LENGTH) {
            return UNAVAILABLE_X_REQUEST_ID;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return UNAVAILABLE_X_REQUEST_ID;
            }
        }
        return value;
    }

    private static final class JdkTransport implements Transport {

        private final HttpClient httpClient;

        private JdkTransport(Duration connectTimeout) {
            httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        }

        @Override
        public TransportResponse post(
                String endpoint,
                String apiKey,
                Duration timeout,
                String body
        ) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            return new TransportResponse(
                    response.statusCode(), response.body(), sanitizeXRequestId(response.headers())
            );
        }
    }
}
