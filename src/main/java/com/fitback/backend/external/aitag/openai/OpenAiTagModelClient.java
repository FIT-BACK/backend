package com.fitback.backend.external.aitag.openai;

import com.fitback.backend.external.aitag.AiTagImage;
import com.fitback.backend.external.aitag.AiTagModelClient;
import com.fitback.backend.external.aitag.AiTagModelOutput;
import com.fitback.backend.external.aitag.AiTagModelRequest;
import com.fitback.backend.external.aitag.AiTagModelResult;
import com.fitback.backend.external.aitag.AiTagRequestIdSanitizer;
import com.fitback.backend.external.aitag.AiTagResponseParser;
import com.fitback.backend.external.aitag.config.AiTagProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiTagModelClient implements AiTagModelClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final int MAX_LOGGED_TYPE_COUNT = 20;
    private static final int MAX_LOGGED_TYPE_LENGTH = 64;
    private static final int SINGLE_ATTEMPT = 1;
    private static final int PRODUCTION_MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MIN_MILLIS = 250L;
    private static final long RETRY_DELAY_RANGE_MILLIS = 251L;
    private static final long MIN_RETRY_ATTEMPT_MILLIS = 250L;
    private static final long MAX_RATE_LIMIT_DURATION_MILLIS = Duration.ofHours(24).toMillis();
    private static final int MAX_RATE_LIMIT_HEADER_LENGTH = 64;
    private static final Set<Integer> RETRYABLE_PROVIDER_STATUSES = Set.of(500, 502, 503, 504);
    private static final Set<String> SAFE_PROVIDER_ERROR_CODES = Set.of(
            "rate_limit_exceeded",
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
            "insufficient_quota"
    );
    private static final Pattern RATE_LIMIT_DURATION_COMPONENT = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)(ms|s|m|h)"
    );
    private static final String UNAVAILABLE_X_REQUEST_ID = AiTagRequestIdSanitizer.UNAVAILABLE;
    private static final Logger log = LoggerFactory.getLogger(OpenAiTagModelClient.class);

    private final AiTagProperties.OpenAi properties;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final AiTagResponseParser responseParser;
    private final Transport transport;
    private final int maxAttempts;
    private final NanoClock clock;
    private final Sleeper sleeper;
    private final Jitter jitter;

    public OpenAiTagModelClient(
            AiTagProperties.OpenAi properties,
            Duration requestTimeout,
            ObjectMapper objectMapper
    ) {
        this(
                properties, requestTimeout, objectMapper, new JdkTransport(requestTimeout),
                SINGLE_ATTEMPT, System::nanoTime, Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(bound)
        );
    }

    OpenAiTagModelClient(
            AiTagProperties.OpenAi properties,
            Duration requestTimeout,
            ObjectMapper objectMapper,
            Transport transport
    ) {
        this(
                properties, requestTimeout, objectMapper, transport,
                SINGLE_ATTEMPT, System::nanoTime, Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(bound)
        );
    }

    public static OpenAiTagModelClient forProduction(
            AiTagProperties.OpenAi properties,
            Duration requestTimeout,
            ObjectMapper objectMapper
    ) {
        return forProduction(
                properties, requestTimeout, objectMapper, new JdkTransport(requestTimeout),
                System::nanoTime, Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(bound)
        );
    }

    static OpenAiTagModelClient forProduction(
            AiTagProperties.OpenAi properties,
            Duration requestTimeout,
            ObjectMapper objectMapper,
            Transport transport,
            NanoClock clock,
            Sleeper sleeper,
            Jitter jitter
    ) {
        return new OpenAiTagModelClient(
                properties, requestTimeout, objectMapper, transport,
                PRODUCTION_MAX_ATTEMPTS, clock, sleeper, jitter
        );
    }

    private OpenAiTagModelClient(
            AiTagProperties.OpenAi properties,
            Duration requestTimeout,
            ObjectMapper objectMapper,
            Transport transport,
            int maxAttempts,
            NanoClock clock,
            Sleeper sleeper,
            Jitter jitter
    ) {
        properties.validateForUse();
        this.properties = properties;
        this.requestTimeout = requestTimeout;
        this.objectMapper = objectMapper;
        this.responseParser = new AiTagResponseParser(objectMapper);
        this.transport = transport;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    @Override
    public AiTagModelResult analyze(AiTagImage image, AiTagModelRequest request) {
        long logicalStartedAt = clock.nanoTime();
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload(image, request));
        } catch (RuntimeException exception) {
            log.warn(
                    "AI tag provider request failed. provider=openai model={} "
                            + "providerErrorCategory=REQUEST_ERROR elapsedMillis={} attemptCount=0 "
                            + "attemptLatencyMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(logicalStartedAt),
                    elapsedMillis(logicalStartedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            ProviderFailure failure = providerFailure(null, "REQUEST_ERROR", null, logicalStartedAt);
            logLogicalFailure(failure, 0, logicalStartedAt);
            throw failure;
        }

        ProviderFailure lastFailure = null;
        for (int attemptCount = 1; attemptCount <= maxAttempts; attemptCount++) {
            long remainingNanos = remainingNanos(logicalStartedAt);
            if (remainingNanos <= 0L) {
                ProviderFailure failure = providerFailure(null, "TIMEOUT", null, logicalStartedAt);
                logLogicalFailure(failure, attemptCount - 1, logicalStartedAt);
                throw failure;
            }
            try {
                AiTagModelResult result = analyzeAttempt(
                        requestBody,
                        Duration.ofNanos(remainingNanos),
                        attemptCount
                );
                logLogicalSuccess(result, attemptCount, logicalStartedAt);
                return result;
            } catch (ProviderFailure failure) {
                lastFailure = failure;
                if (attemptCount == maxAttempts || !isRetryable(failure)) {
                    logLogicalFailure(failure, attemptCount, logicalStartedAt);
                    throw failure;
                }
                long delayMillis = retryDelayMillis();
                if (!hasRetryBudget(logicalStartedAt, delayMillis)) {
                    logLogicalFailure(failure, attemptCount, logicalStartedAt);
                    throw failure;
                }
                try {
                    sleeper.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    ProviderFailure interrupted = providerFailure(
                            null, "INTERRUPTED", null, logicalStartedAt
                    );
                    logLogicalFailure(interrupted, attemptCount, logicalStartedAt);
                    throw interrupted;
                }
                if (remainingNanos(logicalStartedAt)
                        < Duration.ofMillis(MIN_RETRY_ATTEMPT_MILLIS).toNanos()) {
                    logLogicalFailure(failure, attemptCount, logicalStartedAt);
                    throw failure;
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("provider retry loop exhausted without a result")
                : lastFailure;
    }

    private AiTagModelResult analyzeAttempt(
            String requestBody,
            Duration attemptTimeout,
            int attemptCount
    ) {
        long startedAt = clock.nanoTime();
        TransportResponse response;
        try {
            response = transport.post(
                    ENDPOINT,
                    properties.apiKey(),
                    attemptTimeout,
                    requestBody
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn(
                    "AI tag provider call interrupted. provider=openai model={} providerErrorCategory=INTERRUPTED "
                            + "elapsedMillis={} attemptCount={} attemptLatencyMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    attemptCount,
                    elapsedMillis(startedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            throw providerFailure(null, "INTERRUPTED", null, startedAt);
        } catch (HttpTimeoutException exception) {
            log.warn(
                    "AI tag provider call failed. provider=openai model={} providerErrorCategory=TIMEOUT "
                            + "elapsedMillis={} attemptCount={} attemptLatencyMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    attemptCount,
                    elapsedMillis(startedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            throw providerFailure(null, "TIMEOUT", null, startedAt);
        } catch (IOException exception) {
            log.warn(
                    "AI tag provider call failed. provider=openai model={} providerErrorCategory=TRANSPORT_ERROR "
                            + "elapsedMillis={} attemptCount={} attemptLatencyMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    attemptCount,
                    elapsedMillis(startedAt),
                    UNAVAILABLE_X_REQUEST_ID
            );
            throw providerFailure(null, "TRANSPORT_ERROR", null, startedAt);
        } catch (RuntimeException exception) {
            log.warn(
                    "AI tag provider request failed. provider=openai model={} providerErrorCategory=REQUEST_ERROR "
                            + "elapsedMillis={} attemptCount={} attemptLatencyMillis={} xRequestId={}",
                    properties.model(),
                    elapsedMillis(startedAt),
                    attemptCount,
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
                            + "providerErrorCategory={} providerErrorCode={} rateLimitMetadata={} "
                            + "elapsedMillis={} attemptCount={} "
                            + "attemptLatencyMillis={} xRequestId={}",
                    properties.model(),
                    response.statusCode(),
                    response.statusCode(),
                    metadata.incompleteDetailsReason(),
                    metadata.outputTypes(),
                    metadata.contentTypes(),
                    providerErrorCategory(response.statusCode()),
                    metadata.providerErrorCode(),
                    response.rateLimitMetadata(),
                    elapsedMillis(startedAt),
                    attemptCount,
                    elapsedMillis(startedAt),
                    response.xRequestId()
            );
            throw providerFailure(
                    response.statusCode(), providerErrorCategory(response.statusCode()), null, startedAt,
                    response.xRequestId(), metadata.providerErrorCode(), response.rateLimitMetadata()
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
                    startedAt,
                    attemptCount
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
                    startedAt,
                    attemptCount
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
            logResponseParsingFailure(
                    response, metadata, exception.category(), startedAt, attemptCount
            );
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
                    startedAt,
                    attemptCount
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
                    startedAt,
                    attemptCount
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
                contentTypeNames(root.path("output")),
                safeProviderErrorCode(root.path("error").path("code"))
        );
    }

    private static String safeProviderErrorCode(JsonNode node) {
        String code = safeToken(node);
        return SAFE_PROVIDER_ERROR_CODES.contains(code) ? code : "UNKNOWN";
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
            long startedAt,
            int attemptCount
    ) {
        log.warn(
                "AI tag provider response parsing failed. provider=openai model={} responseStatus={} "
                        + "incompleteDetailsReason={} outputTypes={} contentTypes={} "
                        + "responseParsingCategory={} elapsedMillis={} attemptCount={} "
                        + "attemptLatencyMillis={} xRequestId={}",
                properties.model(),
                response.statusCode(),
                metadata.incompleteDetailsReason(),
                metadata.outputTypes(),
                metadata.contentTypes(),
                category,
                elapsedMillis(startedAt),
                attemptCount,
                elapsedMillis(startedAt),
                response.xRequestId()
        );
    }

    private boolean isRetryable(ProviderFailure failure) {
        return failure.providerHttpStatus() != null
                && RETRYABLE_PROVIDER_STATUSES.contains(failure.providerHttpStatus())
                && "SERVER_ERROR".equals(failure.providerErrorCategory())
                && failure.responseParsingCategory() == null;
    }

    private static boolean isProvider5xx(ProviderFailure failure) {
        return failure.providerHttpStatus() != null
                && failure.providerHttpStatus() >= 500
                && failure.providerHttpStatus() < 600;
    }

    private long retryDelayMillis() {
        return RETRY_DELAY_MIN_MILLIS + jitter.nextLong(RETRY_DELAY_RANGE_MILLIS);
    }

    private boolean hasRetryBudget(long logicalStartedAt, long delayMillis) {
        long requiredNanos = Duration.ofMillis(delayMillis + MIN_RETRY_ATTEMPT_MILLIS).toNanos();
        return remainingNanos(logicalStartedAt) >= requiredNanos;
    }

    private long remainingNanos(long logicalStartedAt) {
        long elapsedNanos = Math.max(0L, clock.nanoTime() - logicalStartedAt);
        return Math.max(0L, requestTimeout.toNanos() - elapsedNanos);
    }

    private void logLogicalSuccess(
            AiTagModelResult result,
            int attemptCount,
            long logicalStartedAt
    ) {
        if (maxAttempts == SINGLE_ATTEMPT) {
            return;
        }
        log.info(
                "AI tag provider logical request completed. provider=openai model={} "
                        + "logicalRequestCount=1 providerAttemptCount={} attemptCount={} "
                        + "recoveredByRetry={} final5xx=false logicalLatencyMillis={} "
                        + "attemptLatencyMillis={} xRequestId={}",
                properties.model(),
                attemptCount,
                attemptCount,
                attemptCount > 1,
                elapsedMillis(logicalStartedAt),
                result.elapsedMillis(),
                result.xRequestId()
        );
    }

    private void logLogicalFailure(
            ProviderFailure failure,
            int attemptCount,
            long logicalStartedAt
    ) {
        if (maxAttempts == SINGLE_ATTEMPT) {
            return;
        }
        log.warn(
                "AI tag provider logical request failed. provider=openai model={} "
                        + "logicalRequestCount=1 providerAttemptCount={} attemptCount={} "
                        + "recoveredByRetry=false final5xx={} logicalLatencyMillis={} "
                        + "attemptLatencyMillis={} httpStatus={} providerErrorCategory={} "
                        + "responseParsingCategory={} xRequestId={}",
                properties.model(),
                attemptCount,
                attemptCount,
                isProvider5xx(failure),
                elapsedMillis(logicalStartedAt),
                failure.elapsedMillis(),
                failure.providerHttpStatus(),
                failure.providerErrorCategory(),
                failure.responseParsingCategory(),
                failure.xRequestId()
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

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, clock.nanoTime() - startedAt) / 1_000_000L;
    }

    private record ResponseMetadata(
            String incompleteDetailsReason,
            List<String> outputTypes,
            List<String> contentTypes,
            String providerErrorCode
    ) {

        private static ResponseMetadata unavailable() {
            return new ResponseMetadata("UNKNOWN", List.of(), List.of(), "UNKNOWN");
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

    private ProviderFailure providerFailure(
            Integer providerHttpStatus,
            String providerErrorCategory,
            String responseParsingCategory,
            long startedAt
    ) {
        return providerFailure(
                providerHttpStatus, providerErrorCategory, responseParsingCategory, startedAt,
                UNAVAILABLE_X_REQUEST_ID, "UNKNOWN", null
        );
    }

    private ProviderFailure providerFailure(
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
                sanitizeXRequestId(xRequestId),
                "UNKNOWN",
                null
        );
    }

    private ProviderFailure providerFailure(
            Integer providerHttpStatus,
            String providerErrorCategory,
            String responseParsingCategory,
            long startedAt,
            String xRequestId,
            String providerErrorCode,
            RateLimitMetadata rateLimitMetadata
    ) {
        return new ProviderFailure(
                providerHttpStatus,
                providerErrorCategory,
                responseParsingCategory,
                elapsedMillis(startedAt),
                sanitizeXRequestId(xRequestId),
                providerErrorCode,
                rateLimitMetadata
        );
    }

    public static final class ProviderFailure extends BusinessException {

        private final Integer providerHttpStatus;
        private final String providerErrorCategory;
        private final String responseParsingCategory;
        private final long elapsedMillis;
        private final String xRequestId;
        private final String providerErrorCode;
        private final RateLimitMetadata rateLimitMetadata;

        private ProviderFailure(
                Integer providerHttpStatus,
                String providerErrorCategory,
                String responseParsingCategory,
                long elapsedMillis,
                String xRequestId,
                String providerErrorCode,
                RateLimitMetadata rateLimitMetadata
        ) {
            super(ErrorCode.ANALYSIS_NOT_READY);
            this.providerHttpStatus = providerHttpStatus;
            this.providerErrorCategory = providerErrorCategory;
            this.responseParsingCategory = responseParsingCategory;
            this.elapsedMillis = elapsedMillis;
            this.xRequestId = xRequestId;
            this.providerErrorCode = providerErrorCode != null
                    && SAFE_PROVIDER_ERROR_CODES.contains(providerErrorCode)
                    ? providerErrorCode
                    : "UNKNOWN";
            this.rateLimitMetadata = rateLimitMetadata;
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

        public String providerErrorCode() {
            return providerErrorCode;
        }

        public RateLimitMetadata rateLimitMetadata() {
            return rateLimitMetadata;
        }
    }

    @FunctionalInterface
    interface Transport {
        TransportResponse post(String endpoint, String apiKey, Duration timeout, String body)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface NanoClock {
        long nanoTime();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface Jitter {
        long nextLong(long boundExclusive);
    }

    record TransportResponse(
            int statusCode,
            String body,
            String xRequestId,
            RateLimitMetadata rateLimitMetadata
    ) {
        TransportResponse(int statusCode, String body) {
            this(statusCode, body, UNAVAILABLE_X_REQUEST_ID, null);
        }

        TransportResponse(int statusCode, String body, String xRequestId) {
            this(statusCode, body, xRequestId, null);
        }

        TransportResponse {
            xRequestId = sanitizeXRequestId(xRequestId);
        }
    }

    static String sanitizeXRequestId(HttpHeaders headers) {
        return sanitizeXRequestId(headers == null ? null : headers.firstValue("x-request-id").orElse(null));
    }

    static String sanitizeXRequestId(String value) {
        return AiTagRequestIdSanitizer.sanitize(value);
    }

    static RateLimitMetadata rateLimitMetadata(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        RateLimitMetadata metadata = new RateLimitMetadata(
                parseRetryAfterMillis(headers.firstValue("retry-after").orElse(null)),
                parseNonNegativeLong(headers.firstValue("x-ratelimit-remaining-requests").orElse(null)),
                parseNonNegativeLong(headers.firstValue("x-ratelimit-remaining-tokens").orElse(null)),
                parseDurationMillis(headers.firstValue("x-ratelimit-reset-requests").orElse(null)),
                parseDurationMillis(headers.firstValue("x-ratelimit-reset-tokens").orElse(null)),
                parseNonNegativeLong(
                        headers.firstValue("x-ratelimit-remaining-project-tokens").orElse(null)
                ),
                parseDurationMillis(
                        headers.firstValue("x-ratelimit-reset-project-tokens").orElse(null)
                )
        );
        return metadata.isEmpty() ? null : metadata;
    }

    private static Long parseRetryAfterMillis(String value) {
        if (!isBoundedHeaderValue(value)) {
            return null;
        }
        try {
            BigDecimal millis = new BigDecimal(value).multiply(BigDecimal.valueOf(1_000L));
            return boundedDurationMillis(millis);
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private static Long parseNonNegativeLong(String value) {
        if (!isBoundedHeaderValue(value) || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Long parseDurationMillis(String value) {
        if (!isBoundedHeaderValue(value)) {
            return null;
        }
        Matcher matcher = RATE_LIMIT_DURATION_COMPONENT.matcher(value);
        int position = 0;
        BigDecimal totalMillis = BigDecimal.ZERO;
        try {
            while (matcher.find()) {
                if (matcher.start() != position) {
                    return null;
                }
                BigDecimal component = new BigDecimal(matcher.group(1));
                BigDecimal multiplier = switch (matcher.group(2)) {
                    case "ms" -> BigDecimal.ONE;
                    case "s" -> BigDecimal.valueOf(1_000L);
                    case "m" -> BigDecimal.valueOf(60_000L);
                    case "h" -> BigDecimal.valueOf(3_600_000L);
                    default -> throw new IllegalStateException("unexpected duration unit");
                };
                totalMillis = totalMillis.add(component.multiply(multiplier));
                if (totalMillis.compareTo(BigDecimal.valueOf(MAX_RATE_LIMIT_DURATION_MILLIS)) > 0) {
                    return null;
                }
                position = matcher.end();
            }
            if (position == 0 || position != value.length()) {
                return null;
            }
            return boundedDurationMillis(totalMillis);
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private static boolean isBoundedHeaderValue(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_RATE_LIMIT_HEADER_LENGTH;
    }

    private static Long boundedDurationMillis(BigDecimal millis) {
        if (millis.signum() < 0
                || millis.compareTo(BigDecimal.valueOf(MAX_RATE_LIMIT_DURATION_MILLIS)) > 0) {
            return null;
        }
        return millis.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    public record RateLimitMetadata(
            Long retryAfterMillis,
            Long remainingRequests,
            Long remainingTokens,
            Long resetRequestsMillis,
            Long resetTokensMillis,
            Long remainingProjectTokens,
            Long resetProjectTokensMillis
    ) {
        public RateLimitMetadata {
            retryAfterMillis = boundedDuration(retryAfterMillis);
            remainingRequests = nonNegative(remainingRequests);
            remainingTokens = nonNegative(remainingTokens);
            resetRequestsMillis = boundedDuration(resetRequestsMillis);
            resetTokensMillis = boundedDuration(resetTokensMillis);
            remainingProjectTokens = nonNegative(remainingProjectTokens);
            resetProjectTokensMillis = boundedDuration(resetProjectTokensMillis);
        }

        private static Long boundedDuration(Long value) {
            return value != null && value >= 0L && value <= MAX_RATE_LIMIT_DURATION_MILLIS
                    ? value
                    : null;
        }

        private static Long nonNegative(Long value) {
            return value != null && value >= 0L ? value : null;
        }

        private boolean isEmpty() {
            return retryAfterMillis == null
                    && remainingRequests == null
                    && remainingTokens == null
                    && resetRequestsMillis == null
                    && resetTokensMillis == null
                    && remainingProjectTokens == null
                    && resetProjectTokensMillis == null;
        }
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
                    response.statusCode(), response.body(), sanitizeXRequestId(response.headers()),
                    rateLimitMetadata(response.headers())
            );
        }
    }
}
