package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AiTagRequestFactory {

    static final int MAX_GARMENTS = 3;
    static final int MAX_TAGS_PER_GARMENT = 8;

    public AiTagModelRequest create(List<Tag> catalog) {
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException("tag catalog must not be empty");
        }
        List<Tag> ordered = catalog.stream()
                .sorted(Comparator.comparing(Tag::getTagType).thenComparing(Tag::getTagName))
                .toList();

        Map<TagType, List<String>> namesByType = ordered.stream()
                .collect(Collectors.groupingBy(
                        Tag::getTagType,
                        LinkedHashMap::new,
                        Collectors.mapping(Tag::getTagName, Collectors.toList())
                ));
        String catalogText = namesByType.entrySet().stream()
                .map(entry -> entry.getKey().name() + ": " + String.join(", ", entry.getValue()))
                .collect(Collectors.joining("\n"));

        String prompt = """
                Analyze the visible fashion item in the crop. The crop normally contains one
                primary garment and may show a person-worn garment or a product-only fashion
                image. Classify each returned garment as exactly TOP, BOTTOM, DRESS, or OUTER.
                Return one garment object in the TOP, BOTTOM, DRESS, or OUTER field for each
                visible piece, and null for each non-visible piece. Do not classify DRESS or OUTER
                as TOP. Do not merge tags from different pieces.

                For every garment, inspect all five dimensions independently: SILHOUETTE, COLOR,
                DETAIL, STYLE, and MATERIAL. A dimension may have no result when it is not visibly
                supported.

                Precision policy:
                - Return a sparse set of only high-confidence, visibly supported tags. Inspecting
                  all five dimensions does not require a tag from every type. When uncertain, omit
                  the tag instead of guessing.
                - If no canonical tag is high-confidence, use a precise, high-confidence suggested
                  tag with visible evidence instead of guessing a canonical tag. Every returned
                  garment must still contain at least one canonical or suggested tag.
                - Do not choose the closest canonical tag when the exact attribute is not visibly
                  supported. Do not return competing tags for the same attribute, such as two fits,
                  rises, or lengths.
                - MATERIAL: Return a tag only when positive surface evidence distinguishes it from
                  other canonical materials. Do not infer composition from color, drape, opacity,
                  garment category, or generic fabric appearance. 우븐/시어 is not a fallback for
                  non-knit fabric; regular knit loops or ribs support 니트, not a coarse woven
                  material. If multiple materials remain plausible, omit MATERIAL.
                - SILHOUETTE: Return fit or rise tags only when directly observable. Do not infer
                  body fit from a flat or hanging product image.
                - DETAIL: Mere visibility is insufficient. Return a DETAIL only when it is a
                  dominant, discriminative design cue. Omit routine functional fastenings,
                  closures, or hardware even when clearly visible; standard waist or neckline
                  construction is not identity-defining by itself.
                - STYLE: Prefer one dominant STYLE tag. Consider graphic, distressed, utility,
                  romantic, clean, restrained, tailored, and structured cues. These cues are
                  examples, not exhaustive definitions. Do not use a generic style as a fallback
                  when stronger visible cues support another style.
                - If the image is rotated, mentally correct its orientation before judging it.

                canonicalTags:
                - Select 0 to %d tags only from the exact canonical catalog below.
                - Do not invent, translate, or normalize canonical tag names.
                - Return each selected canonical tag with its matching type.

                suggestedTags:
                - Suggest 0 to %d precise tags that are visibly supported but missing from the
                  canonical catalog.
                - Write each suggested name as a concise Korean noun phrase.
                - Do not copy an exact canonical tag into suggestedTags.
                - Include confidence from 0 to 1 and brief visible evidence in Korean.

                At least one of canonicalTags or suggestedTags must contain a tag for every
                returned garment.

                Never infer an invisible material. Prefer precision over recall and return no
                suggestion when the image does not provide sufficient visible evidence.

                Canonical catalog:
                %s
                """.formatted(
                        MAX_TAGS_PER_GARMENT,
                        MAX_TAGS_PER_GARMENT,
                        catalogText
                ).trim();

        List<String> allTypes = List.of(TagType.values()).stream().map(Enum::name).toList();
        Map<String, Object> canonicalItem = new LinkedHashMap<>();
        canonicalItem.put("anyOf", namesByType.entrySet().stream()
                .map(entry -> Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "type", Map.of(
                                        "type", "string",
                                        "enum", List.of(entry.getKey().name())
                                ),
                                "name", Map.of("type", "string", "enum", entry.getValue())
                        ),
                        "required", List.of("type", "name")
                ))
                .toList());

        Map<String, Object> suggestionItem = new LinkedHashMap<>();
        suggestionItem.put("type", "object");
        suggestionItem.put("additionalProperties", false);
        suggestionItem.put("properties", Map.of(
                "type", Map.of("type", "string", "enum", allTypes),
                "name", Map.of("type", "string"),
                "confidence", Map.of("type", "number"),
                "evidence", Map.of("type", "string")
        ));
        suggestionItem.put("required", List.of("type", "name", "confidence", "evidence"));

        Map<String, Object> garmentItem = new LinkedHashMap<>();
        garmentItem.put("type", "object");
        garmentItem.put("additionalProperties", false);
        Map<String, Object> garmentProperties = new LinkedHashMap<>();
        garmentProperties.put("canonicalTags", tagArray(canonicalItem));
        garmentProperties.put("suggestedTags", tagArray(suggestionItem));
        garmentItem.put("properties", garmentProperties);
        garmentItem.put("required", requiredGarmentFields());
        garmentItem.put("anyOf", List.of(
                garmentSchemaWithTagMinimum(garmentProperties, "canonicalTags"),
                garmentSchemaWithTagMinimum(garmentProperties, "suggestedTags")
        ));

        Map<String, Object> garmentsProperties = new LinkedHashMap<>();
        for (GarmentPiece piece : GarmentPiece.values()) {
            garmentsProperties.put(piece.name(), Map.of(
                    "anyOf", List.of(
                            Map.of("type", "null"),
                            garmentItem
                    )
            ));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of(
                "garments", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", garmentsProperties,
                        "required", garmentPieceNames(),
                        "anyOf", List.of(GarmentPiece.values()).stream()
                                .flatMap(nonNullPiece -> List.of(GarmentPiece.values()).stream()
                                        .filter(nullPiece -> nullPiece != nonNullPiece)
                                        .map(nullPiece -> garmentsSchemaWithBoundedPieceCount(
                                                garmentsProperties,
                                                garmentItem,
                                                nonNullPiece,
                                                nullPiece
                                        )))
                                .toList()
                )
        ));
        schema.put("required", List.of("garments"));
        return new AiTagModelRequest(prompt, schema);
    }

    private static Map<String, Object> tagArray(Map<String, Object> item) {
        return Map.of(
                "type", "array",
                "minItems", 0,
                "maxItems", MAX_TAGS_PER_GARMENT,
                "items", item
        );
    }

    private static Map<String, Object> garmentSchemaWithTagMinimum(
            Map<String, Object> garmentProperties,
            String tagProperty
    ) {
        Map<String, Object> properties = new LinkedHashMap<>(garmentProperties);
        Map<String, Object> tags = new LinkedHashMap<>(asMap(properties.get(tagProperty)));
        tags.put("minItems", 1);
        properties.put(tagProperty, tags);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", properties,
                "required", requiredGarmentFields()
        );
    }

    private static Map<String, Object> garmentsSchemaWithBoundedPieceCount(
            Map<String, Object> garmentsProperties,
            Map<String, Object> garmentItem,
            GarmentPiece nonNullPiece,
            GarmentPiece nullPiece
    ) {
        Map<String, Object> properties = new LinkedHashMap<>(garmentsProperties);
        properties.put(nonNullPiece.name(), garmentItem);
        properties.put(nullPiece.name(), Map.of("type", "null"));
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", properties,
                "required", garmentPieceNames()
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> requiredGarmentFields() {
        return List.of("canonicalTags", "suggestedTags");
    }

    private static List<String> garmentPieceNames() {
        return List.of(GarmentPiece.values()).stream().map(Enum::name).toList();
    }
}
