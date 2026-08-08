package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AiTagRequestFactory {

    static final int MAX_GARMENTS = GarmentPiece.values().length;
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
                Analyze the visible fashion item or outfit in the image. The image may show a
                person-worn outfit or a product-only fashion image. Return one garment object for
                each visible TOP, BOTTOM, and SHOES piece. Do not merge tags from different pieces.

                For every garment, inspect all five dimensions independently: SILHOUETTE, COLOR,
                DETAIL, STYLE, and MATERIAL. A dimension may have no result when it is not visibly
                supported.

                canonicalTags:
                - Select 0 to %d tags only from the exact canonical catalog below.
                - Do not invent, translate, or normalize canonical tag names.
                - Return every canonical tag with its matching type.

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
        garmentProperties.put("piece", Map.of(
                "type", "string",
                "enum", Arrays.stream(GarmentPiece.values())
                        .map(Enum::name)
                        .toList()
        ));
        garmentProperties.put("canonicalTags", tagArray(canonicalItem));
        garmentProperties.put("suggestedTags", tagArray(suggestionItem));
        garmentItem.put("properties", garmentProperties);
        garmentItem.put("required", requiredGarmentFields());
        garmentItem.put("anyOf", List.of(
                garmentSchemaWithTagMinimum(garmentProperties, "canonicalTags"),
                garmentSchemaWithTagMinimum(garmentProperties, "suggestedTags")
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of(
                "garments", Map.of(
                        "type", "array",
                        "minItems", 1,
                        "maxItems", MAX_GARMENTS,
                        "items", garmentItem
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> requiredGarmentFields() {
        return List.of("piece", "canonicalTags", "suggestedTags");
    }
}
