package com.fitback.backend.domain.analysis.dto;

import java.util.List;

public record AnalysisSummaryResponse(Long reportId, String imageUrl, List<String> tags) {
}
