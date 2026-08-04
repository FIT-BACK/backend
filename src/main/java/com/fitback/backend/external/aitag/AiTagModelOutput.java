package com.fitback.backend.external.aitag;

import java.util.List;

public record AiTagModelOutput(
        List<AiTagPrediction> canonicalTags,
        List<AiTagSuggestion> suggestedTags
) {

    public AiTagModelOutput {
        canonicalTags = List.copyOf(canonicalTags);
        suggestedTags = List.copyOf(suggestedTags);
    }
}
