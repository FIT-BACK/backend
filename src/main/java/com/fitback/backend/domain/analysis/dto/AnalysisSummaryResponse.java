package com.fitback.backend.domain.analysis.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AnalysisSummaryResponse(
        Long reportId,
        String imageUrl,
        List<String> tags,
        LocalDateTime savedAt
) {
}
