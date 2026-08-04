package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.TagType;

public record AiTagPrediction(TagType type, String name) {

    public AiTagPrediction {
        if (type == null) {
            throw new IllegalArgumentException("tag type must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tag name must not be blank");
        }
        name = name.trim();
    }
}
