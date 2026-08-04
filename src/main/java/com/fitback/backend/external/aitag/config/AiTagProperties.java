package com.fitback.backend.external.aitag.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fitback.ai")
public record AiTagProperties(
        String tagAnalyzer,
        Duration requestTimeout,
        OpenAi openai,
        Bedrock bedrock
) {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public AiTagProperties {
        tagAnalyzer = textOrDefault(tagAnalyzer, "unavailable");
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        if (requestTimeout.isZero()
                || requestTimeout.isNegative()
                || requestTimeout.getNano() != 0) {
            throw new IllegalArgumentException(
                    "fitback.ai.request-timeout must be a positive whole-second duration"
            );
        }
        openai = openai == null ? new OpenAi(null, null) : openai;
        bedrock = bedrock == null ? new Bedrock(null, null) : bedrock;
    }

    public record OpenAi(String apiKey, String model) {

        public OpenAi {
            apiKey = textOrEmpty(apiKey);
            model = textOrEmpty(model);
        }

        public void validateForUse() {
            requireText(apiKey, "fitback.ai.openai.api-key");
            requireText(model, "fitback.ai.openai.model");
        }
    }

    public record Bedrock(String region, String modelId) {

        public Bedrock {
            region = textOrEmpty(region);
            modelId = textOrEmpty(modelId);
        }

        public void validateForUse() {
            requireText(region, "fitback.ai.bedrock.region");
            requireText(modelId, "fitback.ai.bedrock.model-id");
        }
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireText(String value, String property) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
    }
}
