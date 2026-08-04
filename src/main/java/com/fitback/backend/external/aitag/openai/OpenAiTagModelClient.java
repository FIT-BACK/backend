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
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiTagModelClient implements AiTagModelClient {

    private final AiTagProperties.OpenAi properties;
    private final ObjectMapper objectMapper;
    private final AiTagResponseParser responseParser;
    private final Transport transport;

    public OpenAiTagModelClient(
            AiTagProperties.OpenAi properties,
            ObjectMapper objectMapper
    ) {
        this(properties, objectMapper, new JdkTransport(properties.timeout()));
    }

    OpenAiTagModelClient(
            AiTagProperties.OpenAi properties,
            ObjectMapper objectMapper,
            Transport transport
    ) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalArgumentException("fitback.ai.openai.api-key must not be blank");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.responseParser = new AiTagResponseParser(objectMapper);
        this.transport = transport;
    }

    @Override
    public AiTagModelResult analyze(AiTagImage image, AiTagModelRequest request) {
        long startedAt = System.nanoTime();
        try {
            TransportResponse response = transport.post(
                    properties.endpoint().toString(),
                    properties.apiKey(),
                    properties.timeout(),
                    objectMapper.writeValueAsString(payload(image, request))
            );
            if (response.statusCode() >= 400) {
                throw notReady();
            }
            JsonNode root = objectMapper.readTree(response.body());
            String outputJson = outputText(root);
            AiTagModelOutput output = responseParser.parse(outputJson);
            return new AiTagModelResult(
                    "openai",
                    properties.model(),
                    output.canonicalTags(),
                    output.suggestedTags(),
                    nullableInt(root.path("usage").path("input_tokens")),
                    nullableInt(root.path("usage").path("output_tokens")),
                    elapsedMillis(startedAt)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw notReady();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
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
