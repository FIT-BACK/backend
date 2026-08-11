package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.tag.entity.TagType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class AiTagResponseParserTest {

    private final AiTagResponseParser parser = new AiTagResponseParser(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"TOP", "BOTTOM", "DRESS", "OUTER"})
    void parsesEveryCropScopedGarmentPiece(String pieceName) {
        String json = """
                {
                  "garments": [
                    {
                      "piece": "%s",
                      "canonicalTags": [{"type": "COLOR", "name": "베이지"}],
                      "suggestedTags": []
                    }
                  ]
                }
                """.formatted(pieceName);

        AiTagModelOutput output = parser.parse(json);

        assertThat(output.garments()).singleElement().satisfies(garment ->
                assertThat(garment.piece()).isEqualTo(GarmentPiece.valueOf(pieceName))
        );
    }

    @Test
    void parsesPieceKeyedGarmentWithMultipleNullableFields() {
        String json = """
                {
                  "garments": {
                    "TOP": null,
                    "BOTTOM": null,
                    "DRESS": null,
                    "OUTER": {
                      "canonicalTags": [{"type": "STYLE", "name": "캐주얼"}],
                      "suggestedTags": []
                    }
                  }
                }
                """;

        AiTagModelOutput output = parser.parse(json);

        assertThat(output.garments())
                .extracting(AiTagGarment::piece)
                .containsExactly(GarmentPiece.OUTER);
    }

    @Test
    void rejectsUnknownPieceKeyInPieceKeyedGarments() {
        String json = """
                {
                  "garments": {
                    "TOP": {"canonicalTags": [{"type": "STYLE", "name": "캐주얼"}], "suggestedTags": []},
                    "BOTTOM": null,
                    "DRESS": null,
                    "OUTER": null,
                    "ACCESSORY": {"canonicalTags": [{"type": "STYLE", "name": "캐주얼"}], "suggestedTags": []}
                  }
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("garments must contain only piece keys");
    }

    @Test
    void parsesCanonicalTagsAndFreeFormSuggestionsForTheSingleGarment() {
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
                    }
                  ]
                }
                """;

        AiTagModelOutput output = parser.parse(json);

        assertThat(output.garments()).hasSize(1);
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
    void rejectsAllNullPieceKeyedGarments() {
        String json = """
                {
                  "garments": {
                    "TOP": null,
                    "BOTTOM": null,
                    "DRESS": null,
                    "OUTER": null
                  }
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("garments must contain exactly 1 item");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void rejectsMultipleGarments(int garmentCount) {
        String[] pieces = {"TOP", "BOTTOM", "DRESS", "OUTER"};
        StringBuilder garments = new StringBuilder();
        for (int index = 0; index < garmentCount; index++) {
            if (index > 0) {
                garments.append(",");
            }
            garments.append("""
                    {"piece":"%s","canonicalTags":[{"type":"STYLE","name":"캐주얼"}],"suggestedTags":[]}
                    """.formatted(pieces[index]));
        }
        String json = """
                {"garments":[%s]}
                """.formatted(garments);

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("garments must contain exactly 1 item");
    }

    @Test
    void rejectsLegacyShoesModelOutput() {
        String json = """
                {
                  "garments": [
                    {
                      "piece": "SHOES",
                      "canonicalTags": [{"type": "MATERIAL", "name": "가죽"}],
                      "suggestedTags": []
                    }
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No enum constant")
                .hasMessageContaining("GarmentPiece.SHOES");
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
