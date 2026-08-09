package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagTargetClothing;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AiTagRequestFactoryTest {

    @Test
    void createsGarmentScopedCanonicalAndFreeFormSuggestionSchema() {
        AiTagModelRequest request = new AiTagRequestFactory().create(List.of(
                Tag.create("와이드핏", TagType.SILHOUETTE, List.of(TagTargetClothing.PANTS)),
                Tag.create("베이지", TagType.COLOR, List.of(TagTargetClothing.ALL)),
                Tag.create("캐주얼", TagType.STYLE, List.of(TagTargetClothing.ALL)),
                Tag.create("데님", TagType.MATERIAL, List.of(TagTargetClothing.ALL))
        ));

        Map<String, Object> rootProperties = map(request.jsonSchema().get("properties"));
        Map<String, Object> garments = map(rootProperties.get("garments"));
        Map<String, Object> garmentsProperties = map(garments.get("properties"));
        Map<String, Object> top = map(garmentsProperties.get("TOP"));
        List<Map<String, Object>> topOptions = maps(top.get("anyOf"));
        Map<String, Object> topGarment = topOptions.stream()
                .filter(option -> "object".equals(option.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> garmentProperties = map(topGarment.get("properties"));
        Map<String, Object> canonicalTags = map(garmentProperties.get("canonicalTags"));
        List<Map<String, Object>> canonicalOptions = maps(
                map(canonicalTags.get("items")).get("anyOf")
        );
        Map<String, List<String>> canonicalNamesByType = canonicalOptions.stream()
                .collect(Collectors.toMap(
                        option -> strings(objectProperty(option, "type").get("enum")).getFirst(),
                        option -> strings(objectProperty(option, "name").get("enum"))
                ));
        Map<String, Object> suggestedTags = map(garmentProperties.get("suggestedTags"));
        Map<String, Object> suggestionName = itemProperty(suggestedTags, "name");
        List<Map<String, Object>> requiredTagAlternatives = maps(topGarment.get("anyOf"));

        assertThat(request.jsonSchema().get("required")).isEqualTo(List.of("garments"));
        assertThat(garments)
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false)
                .containsEntry("required", List.of("TOP", "BOTTOM", "SHOES"));
        assertThat(garmentsProperties).containsOnlyKeys("TOP", "BOTTOM", "SHOES");
        assertThat(topOptions).anySatisfy(option -> assertThat(option).containsEntry("type", "null"));
        assertThat(topGarment.get("required"))
                .isEqualTo(List.of("canonicalTags", "suggestedTags"));
        assertThat(canonicalNamesByType)
                .containsEntry("SILHOUETTE", List.of("와이드핏"))
                .containsEntry("COLOR", List.of("베이지"))
                .containsEntry("STYLE", List.of("캐주얼"))
                .containsEntry("MATERIAL", List.of("데님"));
        assertThat(suggestionName)
                .containsEntry("type", "string")
                .doesNotContainKey("enum");
        assertThat(suggestedTags)
                .containsEntry("minItems", 0)
                .containsEntry("maxItems", 8);
        assertThat(requiredTagAlternatives)
                .allSatisfy(alternative -> assertThat(alternative)
                        .containsEntry("type", "object")
                        .containsEntry("additionalProperties", false)
                        .containsEntry(
                                "required",
                                List.of("canonicalTags", "suggestedTags")
                        ));
        assertThat(requiredTagAlternatives).anySatisfy(alternative -> assertThat(
                map(map(alternative.get("properties")).get("canonicalTags"))
        ).containsEntry("minItems", 1));
        assertThat(requiredTagAlternatives).anySatisfy(alternative -> assertThat(
                map(map(alternative.get("properties")).get("suggestedTags"))
        ).containsEntry("minItems", 1));
        assertThat(request.prompt()).contains(
                "product-only fashion image",
                "TOP, BOTTOM, or SHOES field",
                "SILHOUETTE, COLOR",
                "DETAIL, STYLE, and MATERIAL",
                "Korean",
                "Do not copy an exact canonical tag into suggestedTags",
                "At least one of canonicalTags or suggestedTags must contain a tag",
                "visible evidence"
        );
    }

    @Test
    void includesPrecisionFirstCanonicalTagSelectionPolicy() {
        AiTagModelRequest request = new AiTagRequestFactory().create(List.of(
                Tag.create("와이드핏", TagType.SILHOUETTE, List.of(TagTargetClothing.PANTS)),
                Tag.create("코튼", TagType.MATERIAL, List.of(TagTargetClothing.ALL)),
                Tag.create("단추", TagType.DETAIL, List.of(TagTargetClothing.ALL)),
                Tag.create("캐주얼", TagType.STYLE, List.of(TagTargetClothing.ALL))
        ));

        assertThat(request.prompt()).contains(
                "Return a sparse set of only high-confidence, visibly supported tags",
                "does not require a tag from every type",
                "Do not choose the closest canonical tag",
                "Do not return competing tags for the same attribute",
                "Return a tag only when positive surface evidence distinguishes it from",
                "Do not infer composition from color, drape, opacity",
                "우븐/시어 is not a fallback for",
                "regular knit loops or ribs support 니트",
                "If multiple materials remain plausible, omit MATERIAL",
                "Return fit or rise tags only when directly observable",
                "Mere visibility is insufficient",
                "Return a DETAIL only when it is a",
                "dominant, discriminative design cue",
                "Omit routine functional fastenings",
                "standard waist or neckline",
                "construction is not identity-defining by itself",
                "Prefer one dominant STYLE tag",
                "examples, not exhaustive definitions",
                "mentally correct its orientation",
                "use a precise, high-confidence suggested",
                "tag with visible evidence instead of guessing a canonical tag",
                "must still contain at least one canonical or suggested tag"
        ).doesNotContain("Return every canonical tag with its matching type");
    }

    @Test
    void requiresAtLeastOneNonNullGarmentWhileRetainingNullablePieceChoices() {
        AiTagModelRequest request = new AiTagRequestFactory().create(List.of(
                Tag.create("베이지", TagType.COLOR, List.of(TagTargetClothing.ALL))
        ));

        Map<String, Object> garments = map(map(request.jsonSchema().get("properties")).get("garments"));
        Map<String, Object> garmentsProperties = map(garments.get("properties"));
        List<Map<String, Object>> nonNullGarmentAlternatives = maps(garments.get("anyOf"));
        Map<GarmentPiece, Boolean> allNullPieces = Map.of(
                GarmentPiece.TOP, false,
                GarmentPiece.BOTTOM, false,
                GarmentPiece.SHOES, false
        );

        for (GarmentPiece piece : GarmentPiece.values()) {
            List<Map<String, Object>> pieceOptions = maps(
                    map(garmentsProperties.get(piece.name())).get("anyOf")
            );
            assertThat(pieceOptions)
                    .anySatisfy(option -> assertThat(option).containsEntry("type", "null"))
                    .anySatisfy(option -> assertThat(option).containsEntry("type", "object"));
        }
        assertThat(nonNullGarmentAlternatives).hasSize(GarmentPiece.values().length);
        for (GarmentPiece nonNullPiece : GarmentPiece.values()) {
            assertThat(nonNullGarmentAlternatives).anySatisfy(alternative -> {
                assertThat(alternative)
                        .containsEntry("type", "object")
                        .containsEntry("additionalProperties", false)
                        .containsEntry("required", List.of("TOP", "BOTTOM", "SHOES"));
                assertThat(map(map(alternative.get("properties")).get(nonNullPiece.name())))
                        .containsEntry("type", "object");
            });
        }
        assertThat(allowsGarmentPieceCombination(nonNullGarmentAlternatives, allNullPieces)).isFalse();
        for (GarmentPiece nonNullPiece : GarmentPiece.values()) {
            Map<GarmentPiece, Boolean> singleNonNullPiece = new HashMap<>(allNullPieces);
            singleNonNullPiece.put(nonNullPiece, true);

            assertThat(allowsGarmentPieceCombination(
                    nonNullGarmentAlternatives,
                    singleNonNullPiece
            )).isTrue();
        }
    }

    @Test
    void rejectsEmptyCatalogBeforeBuildingSchema() {
        assertThatThrownBy(() -> new AiTagRequestFactory().create(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tag catalog must not be empty");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static boolean allowsGarmentPieceCombination(
            List<Map<String, Object>> alternatives,
            Map<GarmentPiece, Boolean> nonNullPieces
    ) {
        return alternatives.stream().anyMatch(alternative -> List.of(GarmentPiece.values()).stream()
                .allMatch(piece -> {
                    Map<String, Object> pieceSchema = map(
                            map(alternative.get("properties")).get(piece.name())
                    );
                    return !"object".equals(pieceSchema.get("type")) || nonNullPieces.get(piece);
                }));
    }

    private static Map<String, Object> itemProperty(
            Map<String, Object> arraySchema,
            String property
    ) {
        Map<String, Object> item = map(arraySchema.get("items"));
        return objectProperty(item, property);
    }

    private static Map<String, Object> objectProperty(
            Map<String, Object> objectSchema,
            String property
    ) {
        return map(map(objectSchema.get("properties")).get(property));
    }
}
