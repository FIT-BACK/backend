package com.fitback.backend.external.aitag.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiTagPropertiesTest {

    @Test
    void defaultsOpenAiModelToGpt56Luna() {
        AiTagProperties properties = new AiTagProperties(null, null, null);

        assertThat(properties.openai().model()).isEqualTo("gpt-5.6-luna");
    }
}
