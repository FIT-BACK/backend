package com.fitback.backend.domain.analysis.dto;

import java.util.List;

public record AnalysisCreateResponse(
        Long reportId,
        String imageUrl,
        Integer matchPercentage,
        List<SuggestedTagResponse> suggestedTags
) {
}
