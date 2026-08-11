package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.external.aitag.GarmentPiece;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RecommendationInputReaderTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long REPORT_ID = 501L;

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    private final RecommendationInputSnapshotFactory snapshotFactory =
            new RecommendationInputSnapshotFactory();

    @Test
    void returnsSnapshotWhenReportHasOnlyCustomTags() {
        AnalysisReport report = report();
        report.confirmRecommendationInput(List.of(), List.of("출근룩"), 70);
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(
                REPORT_ID,
                MEMBER_ID
        )).thenReturn(Optional.of(report));

        RecommendationInputSnapshot snapshot = reader().read(MEMBER_ID, REPORT_ID);

        assertThat(snapshot.tags()).isEmpty();
        assertThat(snapshot.customTagNames()).containsExactly("출근룩");
        assertThat(snapshot.category()).isEqualTo(ProductCategory.TOP);
    }

    @Test
    void throwsAnalysisNotReadyOnlyWhenKnownAndCustomTagsAreEmpty() {
        AnalysisReport report = report();
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(
                REPORT_ID,
                MEMBER_ID
        )).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reader().read(MEMBER_ID, REPORT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_NOT_READY);
    }

    @Test
    void throwsAnalysisNotReadyWhenLegacyReportHasNoGarmentCategory() {
        AnalysisReport report = report(null);
        report.confirmRecommendationInput(
                List.of(Tag.create("미니멀", TagType.DETAIL)),
                List.of(),
                70
        );
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(
                REPORT_ID,
                MEMBER_ID
        )).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reader().read(MEMBER_ID, REPORT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_NOT_READY);
    }

    private RecommendationInputReader reader() {
        return new RecommendationInputReader(analysisReportRepository, snapshotFactory);
    }

    private AnalysisReport report() {
        return report(GarmentPiece.TOP);
    }

    private AnalysisReport report(GarmentPiece garmentPiece) {
        Member member = Member.create(
                "member@example.com",
                "member",
                "password",
                LoginProvider.EMAIL
        );
        AnalysisReport report = AnalysisReport.create(
                member,
                "/uploads/look.jpg",
                70,
                garmentPiece
        );
        ReflectionTestUtils.setField(report, "id", REPORT_ID);
        return report;
    }
}
