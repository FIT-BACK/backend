package com.fitback.backend.domain.recommendation.service.model;

import java.util.List;

public record RecommendationInputSnapshot(
        Long reportId,
        Long memberId,
        Integer inputRevision,
        List<TagInput> tags
) {

    public RecommendationInputSnapshot {
        tags = List.copyOf(tags);
    }

    public List<Long> tagIds() {
        return tags.stream().map(TagInput::id).toList();
    }

    public List<String> tagNames() {
        return tags.stream().map(TagInput::name).toList();
    }

    public record TagInput(Long id, String name) {
    }
}
