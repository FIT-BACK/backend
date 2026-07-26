package com.fitback.backend.domain.recommendation.service.model;

import java.util.List;

public record RecommendationInputSnapshot(
        Long reportId,
        Long memberId,
        Integer inputRevision,
        Integer matchPercentage,
        List<TagInput> tags
) {

    public RecommendationInputSnapshot {
        tags = List.copyOf(tags);
    }

    public RecommendationInputSnapshot(
            Long reportId,
            Long memberId,
            Integer inputRevision,
            List<TagInput> tags
    ) {
        this(reportId, memberId, inputRevision, 70, tags);
    }

    public List<Long> tagIds() {
        return tags.stream()
                .filter(tag -> tag.key().startsWith("TAG:"))
                .map(tag -> Long.valueOf(tag.key().substring("TAG:".length())))
                .toList();
    }

    public List<String> tagKeys() {
        return tags.stream().map(TagInput::key).toList();
    }

    public List<String> tagNames() {
        return tags.stream().map(TagInput::name).toList();
    }

    public record TagInput(String key, String name) {

        public TagInput(Long id, String name) {
            this("TAG:" + id, name);
        }
    }
}
