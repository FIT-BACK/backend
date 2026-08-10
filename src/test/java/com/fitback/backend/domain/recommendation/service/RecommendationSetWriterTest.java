package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.notification.event.AnalysisCompletedEvent;
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
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class RecommendationSetWriterTest {

    private static final Long REPORT_ID = 501L;
    private static final Long MEMBER_ID = 1L;
    private static final String SCORE_VERSION = "TAG_MATCH_RATIO_V1";

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RecommendationInputSnapshot input() {
        return new RecommendationInputSnapshot(
                REPORT_ID,
                MEMBER_ID,
                1,
                List.of(new TagInput(10L, "Fixture", TagType.DETAIL))
        );
    }

    private RecommendationSetWriter writer() {
        return new RecommendationSetWriter(
                analysisReportRepository,
                productRepository,
                recommendedItemRepository,
                snapshotFactory,
                eventPublisher,
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    //추천 대상이 없는 최소 조건으로 저장 경로를 통과시킴
    private void stubWritableReport(RecommendationInputSnapshot input) {
        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(REPORT_ID, MEMBER_ID))
                .thenReturn(Optional.of(report));
        when(snapshotFactory.matches(report, input)).thenReturn(true);
        when(productRepository.findAllById(List.of())).thenReturn(List.of());
    }

    @Test
    void rejectsChangedInputBeforeDeletingCurrentSet() {
        RecommendationInputSnapshot input = input();
        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(REPORT_ID, MEMBER_ID))
                .thenReturn(Optional.of(report));
        when(snapshotFactory.matches(report, input)).thenReturn(false);

        assertThatThrownBy(() -> writer().replaceCurrentSet(
                input,
                SCORE_VERSION,
                List.of()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_INPUT_CHANGED);
        verify(recommendedItemRepository, never()).deleteCurrentSetByReportId(REPORT_ID);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void publishesAnalysisCompletedEventAfterWritingSetTest() {
        RecommendationInputSnapshot input = input();
        stubWritableReport(input);
        when(report.getId()).thenReturn(REPORT_ID);

        writer().replaceCurrentSet(input, SCORE_VERSION, List.of());

        verify(eventPublisher).publishEvent(new AnalysisCompletedEvent(REPORT_ID, MEMBER_ID));
    }
}
