package com.fitback.backend.external.aitag.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.external.aitag.AiTagImage;
import com.fitback.backend.external.aitag.AiTagModelRequest;
import com.fitback.backend.external.aitag.AiTagModelResult;
import com.fitback.backend.external.aitag.config.AiTagProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiTagModelClientTest {

    @Test
    void sendsImageWithStrictSchemaAndParsesCanonicalTags() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String outputText = objectMapper.writeValueAsString(Map.of(
                "canonicalTags", List.of(Map.of("type", "MATERIAL", "name", "데님")),
                "suggestedTags", List.of(Map.of(
                        "type", "COLOR",
                        "name", "인디고 블루",
                        "confidence", 0.94,
                        "evidence", "짙은 청색 표면"
                ))
        ));
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of(
                        "content", List.of(Map.of(
                                "type", "output_text",
                                "text", outputText
                        ))
                )),
                "usage", Map.of("input_tokens", 20, "output_tokens", 8)
        ));
        AtomicReference<String> requestBody = new AtomicReference<>();
        OpenAiTagModelClient.Transport transport = (endpoint, apiKey, timeout, body) -> {
            requestBody.set(body);
            return new OpenAiTagModelClient.TransportResponse(200, responseBody);
        };
        AiTagProperties.OpenAi properties = new AiTagProperties.OpenAi(
                URI.create("https://example.test/v1/responses"),
                "test-key",
                "test-model",
                Duration.ofSeconds(1)
        );
        OpenAiTagModelClient client = new OpenAiTagModelClient(
                properties,
                objectMapper,
                transport
        );

        AiTagModelResult result = client.analyze(
                new AiTagImage(new byte[]{1, 2, 3}, "image/jpeg"),
                new AiTagModelRequest("analyze", Map.of("type", "object"))
        );

        var payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.path("reasoning").path("effort").asText()).isEqualTo("none");
        assertThat(payload.path("input").get(0).path("content").get(1).path("detail").asText())
                .isEqualTo("original");
        assertThat(requestBody.get()).contains(
                "input_image",
                "data:image/jpeg;base64,AQID",
                "json_schema"
        );
        assertThat(result.canonicalTags()).singleElement().satisfies(prediction -> {
            assertThat(prediction.type()).isEqualTo(TagType.MATERIAL);
            assertThat(prediction.name()).isEqualTo("데님");
        });
        assertThat(result.suggestedTags()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.type()).isEqualTo(TagType.COLOR);
            assertThat(suggestion.name()).isEqualTo("인디고 블루");
            assertThat(suggestion.confidence()).isEqualTo(0.94);
        });
        assertThat(result.inputTokens()).isEqualTo(20);
        assertThat(result.outputTokens()).isEqualTo(8);
    }
}
