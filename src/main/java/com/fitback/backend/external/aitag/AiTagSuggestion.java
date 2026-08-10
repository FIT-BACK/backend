package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.TagType;

public record AiTagSuggestion(
        TagType type,
        String name,
        double confidence,
        String evidence
) {

    public AiTagSuggestion {
        if (type == null) {
            throw new IllegalArgumentException("suggested tag type must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("suggested tag name must not be blank");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("suggested tag confidence must be between 0 and 1");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("suggested tag evidence must not be blank");
        }
        name = name.trim();
        evidence = evidence.trim();
    }
}
