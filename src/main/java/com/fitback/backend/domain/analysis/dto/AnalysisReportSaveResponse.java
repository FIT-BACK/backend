package com.fitback.backend.domain.analysis.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AnalysisReportSaveResponse(
        Long reportId,
        boolean saved,
        LocalDateTime savedAt,
        List<SavedAnalysisItemResponse> selectedItems
) {

    public AnalysisReportSaveResponse {
        selectedItems = List.copyOf(selectedItems);
    }

    public static AnalysisReportSaveResponse unsaved(Long reportId) {
        return new AnalysisReportSaveResponse(reportId, false, null, List.of());
    }
}
