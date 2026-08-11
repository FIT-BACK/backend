package com.fitback.backend.domain.recommendation.service.model;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public record RecommendationInputSnapshot(
        Long reportId,
        Long memberId,
        Integer inputRevision,
        Integer matchPercentage,
        ProductCategory category,
        List<TagInput> tags,
        List<String> customTagNames
) {

    public RecommendationInputSnapshot {
        tags = List.copyOf(tags);
        customTagNames = List.copyOf(customTagNames);
    }

    public RecommendationInputSnapshot(
            Long reportId,
            Long memberId,
            Integer inputRevision,
            Integer matchPercentage,
            ProductCategory category,
            List<TagInput> tags
    ) {
        this(reportId, memberId, inputRevision, matchPercentage, category, tags, List.of());
    }

    public RecommendationInputSnapshot(
            Long reportId,
            Long memberId,
            Integer inputRevision,
            ProductCategory category,
            List<TagInput> tags
    ) {
        this(reportId, memberId, inputRevision, 70, category, tags, List.of());
    }

    public List<Long> tagIds() {
        return tags.stream().map(TagInput::id).toList();
    }

    public List<String> tagKeys() {
        return Stream.concat(
                tags.stream().map(tag -> "TAG:" + tag.id()),
                customTagNames.stream().map(name -> "CUSTOM:" + name.toLowerCase(Locale.ROOT))
        ).toList();
    }

    public List<String> tagNames() {
        return Stream.concat(
                tags.stream().map(TagInput::name),
                customTagNames.stream()
        ).toList();
    }

    public record TagInput(Long id, String name, TagType tagType) {
    }
}
