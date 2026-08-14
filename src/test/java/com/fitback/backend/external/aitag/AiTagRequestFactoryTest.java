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
    void exposesOnlyCropScopedGarmentPieces() {
        assertThat(GarmentPiece.values()).containsExactly(
                GarmentPiece.TOP,
                GarmentPiece.BOTTOM,
                GarmentPiece.DRESS,
                GarmentPiece.OUTER
        );
        assertThat(AiTagRequestFactory.MAX_GARMENTS).isEqualTo(1);
    }

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
        String normalizedPrompt = request.prompt().replaceAll("\\s+", " ");

        assertThat(request.jsonSchema().get("required")).isEqualTo(List.of("garments"));
        assertThat(garments)
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false)
                .containsEntry("required", List.of("TOP", "BOTTOM", "DRESS", "OUTER"));
        assertThat(garmentsProperties).containsOnlyKeys("TOP", "BOTTOM", "DRESS", "OUTER");
        assertThat(topOptions).anySatisfy(option -> assertThat(option).containsEntry("type", "null"));
        assertThat(topGarment.get("required"))
                .isEqualTo(List.of("canonicalTags", "suggestedTags"));
        assertThat(canonicalTags)
                .containsEntry("minItems", 1)
                .containsEntry("maxItems", 8);
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
        assertThat(topGarment).doesNotContainKey("anyOf");
        assertThat(normalizedPrompt).contains(
                "The input contains exactly one cropped garment",
                "Classify it as exactly one of TOP, BOTTOM, DRESS, or OUTER",
                "Never return more than one garment piece",
                "DRESS and OUTER must not be folded into TOP",
                "SILHOUETTE, COLOR",
                "DETAIL, STYLE, and MATERIAL",
                "Korean",
                "Do not copy an exact canonical tag into suggestedTags",
                "canonicalTags must contain at least one tag",
                "visible evidence"
        ).doesNotContain("SHOES");
    }

    @Test
    void includesEvidenceBalancedCanonicalTagSelectionPolicy() {
        AiTagModelRequest request = new AiTagRequestFactory().create(List.of(
                Tag.create("와이드핏", TagType.SILHOUETTE, List.of(TagTargetClothing.PANTS)),
                Tag.create("코튼", TagType.MATERIAL, List.of(TagTargetClothing.ALL)),
                Tag.create("단추", TagType.DETAIL, List.of(TagTargetClothing.ALL)),
                Tag.create("캐주얼", TagType.STYLE, List.of(TagTargetClothing.ALL))
        ));
        String normalizedPrompt = request.prompt().replaceAll("\\s+", " ");

        assertThat(normalizedPrompt).contains(
                "inspect the garment in two passes",
                "Keep evidence for each dimension separate",
                "whole-garment outline and proportions",
                "local structures and surface texture",
                "Return a sparse set of only high-confidence, visibly supported tags",
                "does not require a tag from every type",
                "Do not choose the closest canonical tag",
                "Do not return competing tags for the same attribute",
                "COLOR: Judge the stable garment color across a broad, well-lit region",
                "Do not turn shadow into 블랙 or warm illumination into 브라운",
                "Return a tag only when positive surface evidence distinguishes it from",
                "compare the construction-specific surface evidence for every plausible canonical material",
                "require the winning evidence to repeat across a broad garment region",
                "Do not infer composition from color, drape, opacity",
                "Distressing, seams, hardware, or a generic smooth, matte, or coarse surface",
                "우븐/시어 is not a fallback for",
                "데님 requires a visible diagonal twill weave",
                "not distressing or color alone",
                "regular knit loops or ribs indicate 니트",
                "even when the image is rotated",
                "do not reinterpret repeated knit stitches as 트위드",
                "트위드 requires a visibly woven multicolor or nubby yarn structure rather than uniform repeated knit ribs",
                "If multiple materials remain plausible, omit MATERIAL",
                "garment geometry separately from body fit",
                "complete outline is shown",
                "Evaluate shape or fit and length as independent attributes",
                "A clearly visible neckline, pocket, or pleat can be canonical",
                "button, zipper, or belt only when it is a design-defining feature",
                "partially hidden region is not evidence for a specific DETAIL",
                "Prefer one dominant STYLE tag",
                "Do not infer STYLE from the garment piece or one isolated detail",
                "스트릿 requires explicit street cues",
                "오피스룩 requires tailored or business construction",
                "페미닌 or 러블리 requires holistic romantic or decorative cues",
                "미니멀 can remain dominant",
                "Use 캐주얼 only for visibly relaxed everyday construction",
                "correct its orientation",
                "Only add a precise, high-confidence suggested tag",
                "after selecting at least one visibly supported canonical tag"
        ).doesNotContain(
                "Return every canonical tag with its matching type",
                "Mere visibility is insufficient",
                "standard waist or neckline construction is not identity-defining by itself",
                "Do not infer body fit from a flat or hanging product image"
        );
    }

    @Test
    void requiresExactlyOneNonNullGarmentWhileRetainingNullablePieceChoices() {
        AiTagModelRequest request = new AiTagRequestFactory().create(List.of(
                Tag.create("베이지", TagType.COLOR, List.of(TagTargetClothing.ALL))
        ));

        Map<String, Object> garments = map(map(request.jsonSchema().get("properties")).get("garments"));
        Map<String, Object> garmentsProperties = map(garments.get("properties"));
        List<Map<String, Object>> validGarmentAlternatives = maps(garments.get("anyOf"));
        Map<GarmentPiece, Boolean> allNullPieces = Map.of(
                GarmentPiece.TOP, false,
                GarmentPiece.BOTTOM, false,
                GarmentPiece.DRESS, false,
                GarmentPiece.OUTER, false
        );

        for (GarmentPiece piece : GarmentPiece.values()) {
            List<Map<String, Object>> pieceOptions = maps(
                    map(garmentsProperties.get(piece.name())).get("anyOf")
            );
            assertThat(pieceOptions)
                    .anySatisfy(option -> assertThat(option).containsEntry("type", "null"))
                    .anySatisfy(option -> assertThat(option).containsEntry("type", "object"));
        }
        assertThat(validGarmentAlternatives).hasSize(GarmentPiece.values().length);
        for (GarmentPiece nonNullPiece : GarmentPiece.values()) {
            assertThat(validGarmentAlternatives).anySatisfy(alternative -> {
                assertThat(alternative)
                        .containsEntry("type", "object")
                        .containsEntry("additionalProperties", false)
                        .containsEntry(
                                "required",
                                List.of("TOP", "BOTTOM", "DRESS", "OUTER")
                        );
                assertThat(map(map(alternative.get("properties")).get(nonNullPiece.name())))
                        .containsEntry("type", "object");
            });
        }
        assertThat(allowsGarmentPieceCombination(validGarmentAlternatives, allNullPieces)).isFalse();
        for (GarmentPiece nonNullPiece : GarmentPiece.values()) {
            Map<GarmentPiece, Boolean> singleNonNullPiece = new HashMap<>(allNullPieces);
            singleNonNullPiece.put(nonNullPiece, true);

            assertThat(allowsGarmentPieceCombination(
                    validGarmentAlternatives,
                    singleNonNullPiece
            )).isTrue();
        }
        assertThat(allowsGarmentPieceCombination(
                validGarmentAlternatives,
                Map.of(
                        GarmentPiece.TOP, true,
                        GarmentPiece.BOTTOM, true,
                        GarmentPiece.DRESS, false,
                        GarmentPiece.OUTER, false
                )
        )).isFalse();
        assertThat(allowsGarmentPieceCombination(
                validGarmentAlternatives,
                Map.of(
                        GarmentPiece.TOP, true,
                        GarmentPiece.BOTTOM, true,
                        GarmentPiece.DRESS, true,
                        GarmentPiece.OUTER, true
                )
        )).isFalse();
        assertThat(allowsGarmentPieceCombination(
                validGarmentAlternatives,
                Map.of(
                        GarmentPiece.TOP, true,
                        GarmentPiece.BOTTOM, true,
                        GarmentPiece.DRESS, true,
                        GarmentPiece.OUTER, false
                )
        )).isFalse();
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
                    if ("object".equals(pieceSchema.get("type"))) {
                        return nonNullPieces.get(piece);
                    }
                    if ("null".equals(pieceSchema.get("type"))) {
                        return !nonNullPieces.get(piece);
                    }
                    return true;
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
