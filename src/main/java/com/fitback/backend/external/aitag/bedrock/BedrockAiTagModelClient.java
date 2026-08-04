package com.fitback.backend.external.aitag.bedrock;

import com.fitback.backend.external.aitag.AiTagImage;
import com.fitback.backend.external.aitag.AiTagModelClient;
import com.fitback.backend.external.aitag.AiTagModelOutput;
import com.fitback.backend.external.aitag.AiTagModelRequest;
import com.fitback.backend.external.aitag.AiTagModelResult;
import com.fitback.backend.external.aitag.AiTagResponseParser;
import com.fitback.backend.external.aitag.config.AiTagProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class BedrockAiTagModelClient implements AiTagModelClient {

    private final AiTagProperties.Bedrock properties;
    private final ObjectMapper objectMapper;
    private final AiTagResponseParser responseParser;
    private final BedrockRuntimeClient client;

    public BedrockAiTagModelClient(
            AiTagProperties.Bedrock properties,
            ObjectMapper objectMapper,
            BedrockRuntimeClient client
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.responseParser = new AiTagResponseParser(objectMapper);
        this.client = client;
    }

    @Override
    public AiTagModelResult analyze(AiTagImage image, AiTagModelRequest request) {
        long startedAt = System.nanoTime();
        try {
            String body = objectMapper.writeValueAsString(payload(image, request));
            InvokeModelResponse response = client.invokeModel(InvokeModelRequest.builder()
                    .modelId(properties.modelId())
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(body))
                    .build());
            JsonNode root = objectMapper.readTree(response.body().asUtf8String());
            AiTagModelOutput output = responseParser.parse(toolInput(root));
            return new AiTagModelResult(
                    "bedrock",
                    properties.modelId(),
                    output.garments(),
                    nullableInt(root.path("usage").path("input_tokens")),
                    nullableInt(root.path("usage").path("output_tokens")),
                    elapsedMillis(startedAt)
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
        }
    }

    private Map<String, Object> payload(AiTagImage image, AiTagModelRequest request) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "emit_tags");
        tool.put("description", "Return canonical FIT-BACK tags and free-form suggestions");
        tool.put("input_schema", request.jsonSchema());
        return Map.of(
                "anthropic_version", "bedrock-2023-05-31",
                "max_tokens", 1024,
                "temperature", 0,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "image",
                                        "source", Map.of(
                                                "type", "base64",
                                                "media_type", image.contentType(),
                                                "data", Base64.getEncoder()
                                                        .encodeToString(image.bytes())
                                        )
                                ),
                                Map.of("type", "text", "text", request.prompt())
                        )
                )),
                "tools", List.of(tool),
                "tool_choice", Map.of("type", "tool", "name", "emit_tags")
        );
    }

    private String toolInput(JsonNode root) throws Exception {
        for (JsonNode content : root.path("content")) {
            if ("tool_use".equals(content.path("type").asText())
                    && "emit_tags".equals(content.path("name").asText())) {
                return objectMapper.writeValueAsString(content.path("input"));
            }
        }
        throw new IllegalArgumentException("Bedrock response has no emit_tags tool call");
    }

    private static Integer nullableInt(JsonNode node) {
        return node.isIntegralNumber() ? node.asInt() : null;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
