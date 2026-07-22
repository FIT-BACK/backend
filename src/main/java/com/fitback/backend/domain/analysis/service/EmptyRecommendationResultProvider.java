package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.analysis.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EmptyRecommendationResultProvider implements RecommendationResultProvider {

    @Override
    public List<RecommendationGroupResponse> generateFor(AnalysisReport report) {
        return List.of();
    }

    @Override
    public List<RecommendationGroupResponse> findByReportId(Long reportId) {
        return List.of();
    }
}
