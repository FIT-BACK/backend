package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagTargetClothing;
import com.fitback.backend.domain.tag.entity.TagType;
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
        Map<String, Object> garmentItem = map(garments.get("items"));
        Map<String, Object> garmentProperties = map(garmentItem.get("properties"));
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

        assertThat(request.jsonSchema().get("required")).isEqualTo(List.of("garments"));
        assertThat(garments)
                .containsEntry("minItems", 1)
                .containsEntry("maxItems", 3);
        assertThat(garmentItem.get("required"))
                .isEqualTo(List.of("piece", "canonicalTags", "suggestedTags"));
        assertThat(strings(map(garmentProperties.get("piece")).get("enum")))
                .containsExactly("TOP", "BOTTOM", "SHOES");
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
        assertThat(request.prompt()).contains(
                "product-only fashion image",
                "TOP, BOTTOM, and SHOES",
                "SILHOUETTE, COLOR",
                "DETAIL, STYLE, and MATERIAL",
                "Korean",
                "Do not copy an exact canonical tag into suggestedTags",
                "visible evidence"
        );
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
