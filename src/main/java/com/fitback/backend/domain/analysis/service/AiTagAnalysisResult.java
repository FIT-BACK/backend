package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.external.aitag.GarmentPiece;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AiTagAnalysisResult(
        Optional<GarmentPiece> garmentPiece,
        List<Tag> canonicalTags
) {

    public AiTagAnalysisResult {
        garmentPiece = Objects.requireNonNull(
                garmentPiece,
                "garmentPiece must not be null"
        );
        canonicalTags = List.copyOf(canonicalTags);
    }

    public static AiTagAnalysisResult withGarmentPiece(
            GarmentPiece garmentPiece,
            List<Tag> canonicalTags
    ) {
        return new AiTagAnalysisResult(
                Optional.of(Objects.requireNonNull(
                        garmentPiece,
                        "garmentPiece must not be null"
                )),
                canonicalTags
        );
    }

    public static AiTagAnalysisResult withoutGarmentPiece(List<Tag> canonicalTags) {
        return new AiTagAnalysisResult(Optional.empty(), canonicalTags);
    }
}
