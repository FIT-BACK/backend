package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.TagType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiTagResponseParserTest {

    @Test
    void parsesCanonicalTagsAndFreeFormSuggestions() {
        String json = """
                {
                  "canonicalTags": [
                    {"type": "STYLE", "name": "캐주얼"}
                  ],
                  "suggestedTags": [
                    {
                      "type": "COLOR",
                      "name": "오프화이트",
                      "confidence": 0.91,
                      "evidence": "밝은 크림색 표면"
                    }
                  ]
                }
                """;

        AiTagModelOutput output = new AiTagResponseParser(new ObjectMapper()).parse(json);

        assertThat(output.canonicalTags()).singleElement().satisfies(tag -> {
            assertThat(tag.type()).isEqualTo(TagType.STYLE);
            assertThat(tag.name()).isEqualTo("캐주얼");
        });
        assertThat(output.suggestedTags()).singleElement().satisfies(tag -> {
            assertThat(tag.type()).isEqualTo(TagType.COLOR);
            assertThat(tag.name()).isEqualTo("오프화이트");
            assertThat(tag.confidence()).isEqualTo(0.91);
            assertThat(tag.evidence()).isEqualTo("밝은 크림색 표면");
        });
    }

    @Test
    void acceptsNoFreeFormSuggestions() {
        String json = """
                {
                  "canonicalTags": [{"type": "MATERIAL", "name": "데님"}],
                  "suggestedTags": []
                }
                """;

        AiTagModelOutput output = new AiTagResponseParser(new ObjectMapper()).parse(json);

        assertThat(output.canonicalTags()).hasSize(1);
        assertThat(output.suggestedTags()).isEmpty();
    }
}
