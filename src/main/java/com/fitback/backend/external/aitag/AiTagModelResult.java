package com.fitback.backend.external.aitag;

import java.util.List;

public record AiTagModelResult(
        String provider,
        String model,
        List<AiTagGarment> garments,
        Integer inputTokens,
        Integer outputTokens,
        long elapsedMillis,
        String xRequestId
) {

    public AiTagModelResult(
            String provider,
            String model,
            List<AiTagGarment> garments,
            Integer inputTokens,
            Integer outputTokens,
            long elapsedMillis
    ) {
        this(provider, model, garments, inputTokens, outputTokens, elapsedMillis, "UNAVAILABLE");
    }

    public AiTagModelResult {
        garments = AiTagResults.validateGarments(garments);
        xRequestId = xRequestId == null ? "UNAVAILABLE" : xRequestId;
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
