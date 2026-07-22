package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.analysis.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import java.util.List;

public interface RecommendationResultProvider {

    List<RecommendationGroupResponse> generateFor(AnalysisReport report);

    List<RecommendationGroupResponse> findByReportId(Long reportId);
}
