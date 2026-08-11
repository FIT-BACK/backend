package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.dto.RecommendationGenerateRequest;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.external.aitag.GarmentPiece;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationInputCommandServiceTest {

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private RecommendationInputSnapshotFactory snapshotFactory;

    @Mock
    private AnalysisReport report;

    @Test
    void confirmsInputAndReturnsSnapshotFromLockedReport() {
        RecommendationGenerateRequest request = request(10L);
        Tag tag = Tag.create("미니멀", TagType.DETAIL);
        RecommendationInputSnapshot expected = new RecommendationInputSnapshot(
                501L,
                1L,
                2,
                70,
                ProductCategory.TOP,
                List.of(new RecommendationInputSnapshot.TagInput(
                        10L,
                        "미니멀",
                        TagType.DETAIL
                )),
                List.of("출근룩")
        );
        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(501L, 1L))
                .thenReturn(Optional.of(report));
        when(report.getGarmentPiece()).thenReturn(GarmentPiece.TOP);
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of(tag));
        when(snapshotFactory.from(report, 1L)).thenReturn(expected);

        RecommendationInputSnapshot actual = service().confirmAndRead(1L, 501L, request);

        assertThat(actual).isSameAs(expected);
        verify(report).confirmRecommendationInput(List.of(tag), List.of("출근룩"), 70);
    }

    @Test
    void rejectsUnknownTagBeforeChangingReportInput() {
        RecommendationGenerateRequest request = request(10L);
        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(501L, 1L))
                .thenReturn(Optional.of(report));
        when(report.getGarmentPiece()).thenReturn(GarmentPiece.TOP);
        when(tagRepository.findAllById(List.of(10L))).thenReturn(List.of());

        assertThatThrownBy(() -> service().confirmAndRead(1L, 501L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.TAG_NOT_FOUND);
        verify(report, never()).confirmRecommendationInput(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void rejectsLegacyReportWithoutGarmentCategory() {
        RecommendationGenerateRequest request = request(10L);
        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(501L, 1L))
                .thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service().confirmAndRead(1L, 501L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_NOT_READY);
        verify(report, never()).confirmRecommendationInput(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(tagRepository, never()).findAllById(org.mockito.ArgumentMatchers.anyList());
        verify(snapshotFactory, never()).from(report, 1L);
    }

    private RecommendationInputCommandService service() {
        return new RecommendationInputCommandService(
                analysisReportRepository,
                tagRepository,
                snapshotFactory
        );
    }

    private RecommendationGenerateRequest request(Long tagId) {
        return new RecommendationGenerateRequest(
                List.of(tagId),
                List.of("출근룩"),
                70
        );
    }
}
