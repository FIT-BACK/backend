package com.fitback.backend.external.aitag;

import java.util.LinkedHashSet;
import java.util.List;

public record AiTagGarment(
        GarmentPiece piece,
        List<AiTagPrediction> canonicalTags,
        List<AiTagSuggestion> suggestedTags
) {

    public AiTagGarment {
        if (piece == null) {
            throw new IllegalArgumentException("garment piece must not be null");
        }
        canonicalTags = List.copyOf(canonicalTags);
        suggestedTags = List.copyOf(suggestedTags);
        if (canonicalTags.isEmpty() && suggestedTags.isEmpty()) {
            throw new IllegalArgumentException("garment tags must not be empty");
        }
        if (canonicalTags.size() > AiTagRequestFactory.MAX_TAGS_PER_GARMENT
                || suggestedTags.size() > AiTagRequestFactory.MAX_TAGS_PER_GARMENT) {
            throw new IllegalArgumentException("garment tag limit exceeded");
        }
        if (new LinkedHashSet<>(canonicalTags).size() != canonicalTags.size()) {
            throw new IllegalArgumentException("canonical garment tags must be unique");
        }
    }
}
