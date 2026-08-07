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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
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
                    "AI tag provider call interrupted. provider=openai model={} providerErrorCategory=INTERRUPTED elapsedMillis={}",
                    properties.model(),
                    elapsedMillis(startedAt)
            );
            throw notReady();
        } catch (HttpTimeoutException exception) {
            log.warn(
                    "AI tag provider call failed. provider=openai model={} providerErrorCategory=TIMEOUT elapsedMillis={}",
                    properties.model(),
                    elapsedMillis(startedAt)
            );
            throw notReady();
        } catch (IOException exception) {
            log.warn(
                    "AI tag provider call failed. provider=openai model={} providerErrorCategory=TRANSPORT_ERROR elapsedMillis={}",
                    properties.model(),
                    elapsedMillis(startedAt)
            );
            throw notReady();
        } catch (RuntimeException exception) {
            log.warn(
                    "AI tag provider request failed. provider=openai model={} providerErrorCategory=REQUEST_ERROR elapsedMillis={}",
                    properties.model(),
                    elapsedMillis(startedAt)
            );
            throw notReady();
        }
        if (response.statusCode() >= 400) {
            log.warn(
                    "AI tag provider returned an error. provider=openai model={} httpStatus={} providerErrorCategory={} elapsedMillis={}",
                    properties.model(),
                    response.statusCode(),
                    providerErrorCategory(response.statusCode()),
                    elapsedMillis(startedAt)
            );
            throw notReady();
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String outputJson = outputText(root);
            AiTagModelOutput output = responseParser.parse(outputJson);
            return new AiTagModelResult(
                    "openai",
                    properties.model(),
                    output.garments(),
                    nullableInt(root.path("usage").path("input_tokens")),
                    nullableInt(root.path("usage").path("output_tokens")),
                    elapsedMillis(startedAt)
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn(
                    "AI tag provider response parsing failed. provider=openai model={} responseParsingCategory=INVALID_OR_MISSING_OUTPUT elapsedMillis={}",
                    properties.model(),
                    elapsedMillis(startedAt)
            );
            throw notReady();
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
        for (JsonNode output : root.path("output")) {
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    return content.path("text").asText();
                }
            }
        }
        throw new IllegalArgumentException("OpenAI response has no output_text");
    }

    private static Integer nullableInt(JsonNode node) {
        return node.isIntegralNumber() ? node.asInt() : null;
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

    private static BusinessException notReady() {
        return new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
    }

    @FunctionalInterface
    interface Transport {
        TransportResponse post(String endpoint, String apiKey, Duration timeout, String body)
                throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, String body) {
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
            return new TransportResponse(response.statusCode(), response.body());
        }
    }
}
