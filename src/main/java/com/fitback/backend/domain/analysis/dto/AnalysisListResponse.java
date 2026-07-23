package com.fitback.backend.domain.analysis.dto;

import java.util.List;

public record AnalysisListResponse(
        List<AnalysisSummaryResponse> items,
        Long nextCursor,
        boolean hasNext,
        int pageSize
) {
}
