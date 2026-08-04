package com.fitback.backend.external.aitag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class AiTagPropertiesTest {

    @Test
    void defaultsOpenAiModelToGpt56Luna() {
        AiTagProperties properties = new AiTagProperties(null, null, null);

        assertThat(properties.openai().model()).isEqualTo("gpt-5.6-luna");
    }

    @Test
    void rejectsNonHttpsOpenAiEndpoint() {
        assertThatThrownBy(() -> new AiTagProperties.OpenAi(
                URI.create("http://example.test/v1/responses"),
                "test-key",
                "test-model",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fitback.ai.openai.endpoint must use https");
    }
}
