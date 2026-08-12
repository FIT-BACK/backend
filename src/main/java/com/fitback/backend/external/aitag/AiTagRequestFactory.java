package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AiTagRequestFactory {

    static final int MAX_GARMENTS = 1;
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
                The input contains exactly one cropped garment. It may show a person-worn garment
                or a product-only fashion image. Classify it as exactly one of TOP, BOTTOM, DRESS,
                or OUTER. Return one garment object in the matching field and null for the other
                three fields. Never return more than one garment piece. DRESS and OUTER must not
                be folded into TOP.

                For every garment, inspect all five dimensions independently: SILHOUETTE, COLOR,
                DETAIL, STYLE, and MATERIAL. A dimension may have no result when it is not visibly
                supported. Before selecting tags, inspect the garment in two passes: first correct
                its orientation and inspect the whole-garment outline and proportions; then inspect
                local structures and surface texture. Keep evidence for each dimension separate so
                one detail does not imply a color, material, silhouette, or style.

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
                - COLOR: Judge the stable garment color across a broad, well-lit region. Compare
                  illuminated fabric midtones and highlights, not the background or deepest shadow.
                  Do not turn shadow into 블랙 or warm illumination into 브라운.
                - MATERIAL: Return a tag only when positive surface evidence distinguishes it from
                  other canonical materials. Before selecting a tag, compare the construction-specific
                  surface evidence for every plausible canonical material and require the winning
                  evidence to repeat across a broad garment region. Do not infer composition from
                  color, drape, opacity, garment category, or generic fabric appearance. Distressing,
                  seams, hardware, or a generic smooth, matte, or coarse surface are not material
                  construction evidence. 데님 requires a visible diagonal twill weave, not
                  distressing or color alone. 우븐/시어 is not a fallback for non-knit fabric;
                  regular knit loops or ribs indicate 니트 even when the image is rotated, and do
                  not reinterpret repeated knit stitches as 트위드. 트위드 requires a visibly woven
                  multicolor or nubby yarn structure rather than uniform repeated knit ribs. If
                  multiple materials remain plausible, omit MATERIAL.
                - SILHOUETTE: Judge garment geometry separately from body fit. A flat or hanging
                  product image can support visible width, taper, expansion, and length when the
                  complete outline is shown, but it cannot establish contact with a person's body.
                  Evaluate shape or fit and length as independent attributes, with at most one tag
                  for each attribute. Omit an attribute when the relevant outline is cropped or
                  obscured.
                - DETAIL: Inspect neckline, pockets, pleats, and closures independently. A clearly
                  visible neckline, pocket, or pleat can be canonical even when it is not the most
                  dominant feature. Return a button, zipper, or belt only when it is a design-defining
                  feature rather than routine fastening or hardware. A seam, overlap, shadow, or
                  partially hidden region is not evidence for a specific DETAIL.
                - STYLE: Prefer one dominant STYLE tag and base it on the whole garment. Do not infer
                  STYLE from the garment piece or one isolated detail. 스트릿 requires explicit
                  street cues such as graphics, distressing, or an urban oversized combination, not
                  one utility feature. 오피스룩 requires tailored or business construction, not
                  buttons or pockets alone. 페미닌 or 러블리 requires holistic romantic or decorative
                  cues, not the garment category. 미니멀 can remain dominant when clean, restrained
                  construction defines the garment despite one conventional feature. Use 캐주얼 only
                  for visibly relaxed everyday construction, never as a fallback.

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
                                .map(nonNullPiece -> garmentsSchemaWithSinglePiece(
                                        garmentItem,
                                        nonNullPiece
                                ))
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

    private static Map<String, Object> garmentsSchemaWithSinglePiece(
            Map<String, Object> garmentItem,
            GarmentPiece nonNullPiece
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (GarmentPiece piece : GarmentPiece.values()) {
            properties.put(
                    piece.name(),
                    piece == nonNullPiece ? garmentItem : Map.of("type", "null")
            );
        }
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
