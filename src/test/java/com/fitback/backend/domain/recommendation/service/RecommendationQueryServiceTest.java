package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.product.service.ProductResponseMapper;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationQueryServiceTest {

    private final AnalysisReportRepository analysisReportRepository =
            mock(AnalysisReportRepository.class);
    private final RecommendedItemRepository recommendedItemRepository =
            mock(RecommendedItemRepository.class);
    private final RecommendationQueryService queryService = new RecommendationQueryService(
            analysisReportRepository,
            recommendedItemRepository,
            mock(ProductResponseMapper.class)
    );

    @Test
    void returnsNotGeneratedWithEightEmptyGroups() {
        AnalysisReport report = mock(AnalysisReport.class);
        when(report.getRecommendationGeneratedAt()).thenReturn(null);

        RecommendationResultResponse response = queryService.findFor(report);

        assertThat(response.recommendationStatus())
                .isEqualTo(RecommendationStatus.NOT_GENERATED);
        assertThat(response.recommendationGroups())
                .extracting(RecommendationGroupResponse::category)
                .containsExactly(ProductCategory.values());
        assertThat(response.recommendationGroups())
                .allSatisfy(group -> assertThat(group.items()).isEmpty());
    }

    @Test
    void returnsStaleWhenAnalysisRevisionDiffersFromGeneratedRevision() {
        AnalysisReport report = mock(AnalysisReport.class);
        when(report.getId()).thenReturn(501L);
        when(report.getRecommendationGeneratedAt())
                .thenReturn(Instant.parse("2026-07-25T00:00:00Z"));
        when(report.getResultInputRevision()).thenReturn(1);
        when(report.getResultScoreVersion()).thenReturn("SIMILARITY_V1");
        when(report.hasRecommendationInputRevision(1)).thenReturn(false);
        when(recommendedItemRepository.findByReportIdOrderByCategoryAscRankNoAsc(501L))
                .thenReturn(List.of());

        RecommendationResultResponse response = queryService.findFor(report);

        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.STALE);
        assertThat(response.scoreVersion()).isEqualTo("SIMILARITY_V1");
    }
}
