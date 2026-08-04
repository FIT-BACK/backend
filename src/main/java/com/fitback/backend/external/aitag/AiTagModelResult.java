package com.fitback.backend.external.aitag;

import java.util.List;

public record AiTagModelResult(
        String provider,
        String model,
        List<AiTagPrediction> canonicalTags,
        List<AiTagSuggestion> suggestedTags,
        Integer inputTokens,
        Integer outputTokens,
        long elapsedMillis
) {

    public AiTagModelResult {
        canonicalTags = List.copyOf(canonicalTags);
        suggestedTags = List.copyOf(suggestedTags);
    }

    public AiTagModelResult(
            String provider,
            String model,
            List<AiTagPrediction> canonicalTags,
            Integer inputTokens,
            Integer outputTokens,
            long elapsedMillis
    ) {
        this(
                provider,
                model,
                canonicalTags,
                List.of(),
                inputTokens,
                outputTokens,
                elapsedMillis
        );
    }
}
