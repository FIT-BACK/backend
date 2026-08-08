package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.tag.entity.TagType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiTagResponseParserTest {

    private final AiTagResponseParser parser = new AiTagResponseParser(new ObjectMapper());

    @Test
    void parsesCanonicalTagsAndFreeFormSuggestionsByGarment() {
        String json = """
                {
                  "garments": [
                    {
                      "piece": "TOP",
                      "canonicalTags": [
                        {"type": "STYLE", "name": "캐주얼"}
                      ],
                      "suggestedTags": [
                        {
                          "type": "COLOR",
                          "name": "오프화이트",
                          "confidence": 0.91,
                          "evidence": "상의의 밝은 크림색 표면"
                        }
                      ]
                    },
                    {
                      "piece": "SHOES",
                      "canonicalTags": [
                        {"type": "MATERIAL", "name": "가죽"}
                      ],
                      "suggestedTags": []
                    }
                  ]
                }
                """;

        AiTagModelOutput output = parser.parse(json);

        assertThat(output.garments()).hasSize(2);
        assertThat(output.garments().getFirst()).satisfies(garment -> {
            assertThat(garment.piece()).isEqualTo(GarmentPiece.TOP);
            assertThat(garment.canonicalTags()).singleElement().satisfies(tag -> {
                assertThat(tag.type()).isEqualTo(TagType.STYLE);
                assertThat(tag.name()).isEqualTo("캐주얼");
            });
            assertThat(garment.suggestedTags()).singleElement().satisfies(tag -> {
                assertThat(tag.type()).isEqualTo(TagType.COLOR);
                assertThat(tag.name()).isEqualTo("오프화이트");
                assertThat(tag.confidence()).isEqualTo(0.91);
                assertThat(tag.evidence()).isEqualTo("상의의 밝은 크림색 표면");
            });
        });
        assertThat(output.garments().get(1).piece()).isEqualTo(GarmentPiece.SHOES);
    }

    @Test
    void acceptsGarmentWithCanonicalTagsAndNoFreeFormSuggestions() {
        String json = """
                {
                  "garments": [
                    {
                      "piece": "BOTTOM",
                      "canonicalTags": [{"type": "MATERIAL", "name": "데님"}],
                      "suggestedTags": []
                    }
                  ]
                }
                """;

        AiTagModelOutput output = parser.parse(json);

        assertThat(output.garments()).singleElement().satisfies(garment -> {
            assertThat(garment.piece()).isEqualTo(GarmentPiece.BOTTOM);
            assertThat(garment.canonicalTags()).hasSize(1);
            assertThat(garment.suggestedTags()).isEmpty();
        });
    }

    @Test
    void rejectsGarmentWhoseTwoTagArraysAreEmpty() {
        String json = """
                {
                  "garments": [
                    {
                      "piece": "TOP",
                      "canonicalTags": [],
                      "suggestedTags": []
                    }
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("garment tags must not be empty");
    }

    @Test
    void rejectsDuplicateGarmentPieces() {
        String json = """
                {
                  "garments": [
                    {
                      "piece": "TOP",
                      "canonicalTags": [{"type": "STYLE", "name": "캐주얼"}],
                      "suggestedTags": []
                    },
                    {
                      "piece": "TOP",
                      "canonicalTags": [{"type": "COLOR", "name": "베이지"}],
                      "suggestedTags": []
                    }
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("garment pieces must be unique");
    }
}
