package com.fitback.backend.external.aitag;

import java.util.List;

public record AiTagModelResult(
        String provider,
        String model,
        List<AiTagGarment> garments,
        Integer inputTokens,
        Integer outputTokens,
        long elapsedMillis
) {

    public AiTagModelResult {
        garments = AiTagResults.validateGarments(garments);
    }

    public List<AiTagPrediction> canonicalTags() {
        return garments.stream()
                .flatMap(garment -> garment.canonicalTags().stream())
                .toList();
    }

    public List<AiTagSuggestion> suggestedTags() {
        return garments.stream()
                .flatMap(garment -> garment.suggestedTags().stream())
                .toList();
    }
}
