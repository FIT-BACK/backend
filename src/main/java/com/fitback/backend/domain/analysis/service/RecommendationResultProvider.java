package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;

public interface RecommendationResultProvider {

    RecommendationResultResponse findFor(AnalysisReport report);
}
