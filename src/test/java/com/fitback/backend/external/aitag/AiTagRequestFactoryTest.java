package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiTagRequestFactoryTest {

    @Test
    void createsClosedCanonicalAndFreeFormSuggestionSchema() {
        AiTagModelRequest request = new AiTagRequestFactory().create(List.of(
                Tag.create("와이드핏", TagType.SILHOUETTE),
                Tag.create("베이지톤", TagType.COLOR),
                Tag.create("캐주얼", TagType.STYLE),
                Tag.create("데님", TagType.MATERIAL)
        ));

        Map<String, Object> properties = map(request.jsonSchema().get("properties"));
        Map<String, Object> canonicalTags = map(properties.get("canonicalTags"));
        Map<String, Object> canonicalName = itemProperty(canonicalTags, "name");
        Map<String, Object> suggestedTags = map(properties.get("suggestedTags"));
        Map<String, Object> suggestionName = itemProperty(suggestedTags, "name");

        assertThat(request.jsonSchema().get("required"))
                .isEqualTo(List.of("canonicalTags", "suggestedTags"));
        assertThat(strings(canonicalName.get("enum")))
                .containsExactlyInAnyOrder("베이지톤", "데님", "와이드핏", "캐주얼");
        assertThat(suggestionName)
                .containsEntry("type", "string")
                .doesNotContainKey("enum");
        assertThat(suggestedTags)
                .containsEntry("minItems", 0)
                .containsEntry("maxItems", 8);
        assertThat(request.prompt()).contains(
                "product-only fashion image",
                "Korean",
                "Do not copy an exact canonical tag into suggestedTags",
                "visible evidence"
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    private static Map<String, Object> itemProperty(
            Map<String, Object> arraySchema,
            String property
    ) {
        Map<String, Object> item = map(arraySchema.get("items"));
        return map(map(item.get("properties")).get(property));
    }
}
