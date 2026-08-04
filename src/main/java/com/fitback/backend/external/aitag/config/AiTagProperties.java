package com.fitback.backend.external.aitag.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fitback.ai")
public record AiTagProperties(
        String tagAnalyzer,
        OpenAi openai,
        Bedrock bedrock
) {

    public AiTagProperties {
        tagAnalyzer = textOrDefault(tagAnalyzer, "unavailable");
        openai = openai == null ? new OpenAi(null, null, null, null) : openai;
        bedrock = bedrock == null ? new Bedrock(null, null, null, null) : bedrock;
    }

    public record OpenAi(URI endpoint, String apiKey, String model, Duration timeout) {

        public OpenAi {
            endpoint = endpoint == null
                    ? URI.create("https://api.openai.com/v1/responses")
                    : endpoint;
            if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("fitback.ai.openai.endpoint must use https");
            }
            apiKey = apiKey == null ? "" : apiKey.trim();
            model = textOrDefault(model, "gpt-5.6-luna");
            timeout = positiveOrDefault(timeout, Duration.ofSeconds(30), "openai.timeout");
        }
    }

    public record Bedrock(
            String region,
            String modelId,
            Duration apiCallTimeout,
            Duration apiCallAttemptTimeout
    ) {

        public Bedrock {
            region = textOrDefault(region, "ap-northeast-2");
            modelId = textOrDefault(
                    modelId,
                    "global.anthropic.claude-haiku-4-5-20251001-v1:0"
            );
            apiCallTimeout = positiveOrDefault(
                    apiCallTimeout,
                    Duration.ofSeconds(30),
                    "bedrock.api-call-timeout"
            );
            apiCallAttemptTimeout = positiveOrDefault(
                    apiCallAttemptTimeout,
                    Duration.ofSeconds(25),
                    "bedrock.api-call-attempt-timeout"
            );
            if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
                throw new IllegalArgumentException(
                        "fitback.ai.bedrock.api-call-attempt-timeout must not exceed api-call-timeout"
                );
            }
        }
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static Duration positiveOrDefault(
            Duration value,
            Duration defaultValue,
            String property
    ) {
        Duration resolved = value == null ? defaultValue : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException("fitback.ai." + property + " must be positive");
        }
        return resolved;
    }
}
