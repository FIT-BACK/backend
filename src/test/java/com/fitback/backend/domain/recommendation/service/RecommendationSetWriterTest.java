package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationSetWriterTest {

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RecommendedItemRepository recommendedItemRepository;

    @Mock
    private RecommendationInputSnapshotFactory snapshotFactory;

    @Mock
    private AnalysisReport report;

    @Test
    void rejectsChangedInputBeforeDeletingCurrentSet() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                List.of(new TagInput(10L, "Fixture", TagType.DETAIL))
        );
        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(501L, 1L))
                .thenReturn(Optional.of(report));
        when(snapshotFactory.matches(report, input)).thenReturn(false);
        RecommendationSetWriter writer = new RecommendationSetWriter(
                analysisReportRepository,
                productRepository,
                recommendedItemRepository,
                snapshotFactory,
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> writer.replaceCurrentSet(
                input,
                "TAG_MATCH_RATIO_V1",
                List.of()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_INPUT_CHANGED);
        verify(recommendedItemRepository, never()).deleteCurrentSetByReportId(501L);
    }

}
