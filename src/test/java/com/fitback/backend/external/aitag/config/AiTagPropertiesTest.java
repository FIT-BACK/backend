package com.fitback.backend.external.aitag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AiTagPropertiesTest {

    @Test
    void defaultsRequestTimeoutToThirtySeconds() {
        AiTagProperties properties = new AiTagProperties(null, null, null, null);

        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsSubSecondRequestTimeout() {
        assertThatThrownBy(() -> new AiTagProperties(
                null,
                Duration.ofMillis(500),
                null,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "fitback.ai.request-timeout must be a positive whole-second duration"
                );
    }

    @Test
    void keepsProviderSettingsBlankUntilThatProviderIsSelected() {
        AiTagProperties properties = new AiTagProperties(null, null, null, null);

        assertThat(properties.openai().apiKey()).isEmpty();
        assertThat(properties.openai().model()).isEmpty();
        assertThat(properties.bedrock().region()).isEmpty();
        assertThat(properties.bedrock().modelId()).isEmpty();
    }

    @Test
    void selectedProvidersRejectIncompleteSettings() {
        assertThatThrownBy(() -> new AiTagProperties.OpenAi("", "model").validateForUse())
                .hasMessage("fitback.ai.openai.api-key must not be blank");
        assertThatThrownBy(() -> new AiTagProperties.OpenAi("key", "").validateForUse())
                .hasMessage("fitback.ai.openai.model must not be blank");
        assertThatThrownBy(() -> new AiTagProperties.Bedrock("", "model").validateForUse())
                .hasMessage("fitback.ai.bedrock.region must not be blank");
        assertThatThrownBy(() -> new AiTagProperties.Bedrock("region", "").validateForUse())
                .hasMessage("fitback.ai.bedrock.model-id must not be blank");
    }
}
