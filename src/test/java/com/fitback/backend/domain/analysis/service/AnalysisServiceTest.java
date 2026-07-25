package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.dto.AnalysisByImageRequest;
import com.fitback.backend.domain.analysis.dto.AnalysisCreateResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisDetailResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisListResponse;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.service.ImageUploadService;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ImageStorage imageStorage;

    @Mock
    private AiTagAnalyzer aiTagAnalyzer;

    @Mock
    private RecommendationResultProvider recommendationResultProvider;

    @Mock
    private ImageUploadService imageUploadService;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-22T00:00:00Z"),
            ZoneOffset.UTC
    );

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(
                analysisReportRepository,
                memberRepository,
                imageStorage,
                aiTagAnalyzer,
                recommendationResultProvider,
                imageUploadService,
                clock
        );
    }

    @Test
    void createsReportFromCompletedImageAsset() {
        Member member = member(1L);
        Tag minimal = tag(10L, "미니멀");
        Image image = Image.createPending(
                "image-public-id",
                member,
                "prod/images/analysis_original/2026/07/image.jpg",
                ImagePurpose.ANALYSIS_ORIGINAL,
                "image/jpeg",
                3,
                ImageVisibility.PRIVATE,
                clock.instant().plusSeconds(300)
        );
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(imageUploadService.activateAnalysisImage(1L, "image-public-id"))
                .thenReturn(image);
        when(aiTagAnalyzer.analyze(image)).thenReturn(List.of(minimal));
        when(imageUploadService.createReadUrl(image))
                .thenReturn("https://cdn.example.com/signed-image");
        when(analysisReportRepository.save(any(AnalysisReport.class))).thenAnswer(invocation -> {
            AnalysisReport report = invocation.getArgument(0);
            ReflectionTestUtils.setField(report, "id", 502L);
            return report;
        });

        AnalysisCreateResponse response = analysisService.create(
                1L,
                new AnalysisByImageRequest("image-public-id")
        );

        assertThat(response.reportId()).isEqualTo(502L);
        assertThat(response.imageUrl()).isEqualTo("https://cdn.example.com/signed-image");
        assertThat(response.suggestedTags()).extracting("tagName").containsExactly("미니멀");
    }

    @Test
    void createsReportWithUploadedImageAndAiTags() {
        Member member = member(1L);
        Tag minimal = tag(10L, "미니멀");
        Tag wideFit = tag(20L, "와이드핏");
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "look.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(imageStorage.store(image)).thenReturn("/uploads/look.jpg");
        when(aiTagAnalyzer.analyze(image)).thenReturn(List.of(minimal, wideFit));
        when(analysisReportRepository.save(any(AnalysisReport.class))).thenAnswer(invocation -> {
            AnalysisReport report = invocation.getArgument(0);
            ReflectionTestUtils.setField(report, "id", 501L);
            return report;
        });

        AnalysisCreateResponse response = analysisService.create(1L, image);

        assertThat(response.reportId()).isEqualTo(501L);
        assertThat(response.imageUrl()).isEqualTo("/uploads/look.jpg");
        assertThat(response.matchPercentage()).isEqualTo(70);
        assertThat(response.suggestedTags())
                .extracting("tagId", "tagName")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, "미니멀"),
                        org.assertj.core.groups.Tuple.tuple(20L, "와이드핏")
                );
    }

    @Test
    void deletesMultipartImageWhenAnalysisFails() {
        Member member = member(1L);
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "look.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(imageStorage.store(image)).thenReturn("/uploads/look.jpg");
        when(aiTagAnalyzer.analyze(image)).thenThrow(new IllegalStateException("AI failed"));

        assertThatThrownBy(() -> analysisService.create(1L, image))
                .isInstanceOf(IllegalStateException.class);

        verify(imageStorage).delete("/uploads/look.jpg");
    }

    @Test
    void listsReportsWithCursorMetadata() {
        Member member = member(1L);
        Tag minimal = tag(10L, "미니멀");
        AnalysisReport newestReport = report(501L, member, minimal);
        AnalysisReport nextReport = report(500L, member, minimal);
        when(analysisReportRepository.findByMemberIdAndDeletedAtIsNullOrderByIdDesc(
                1L,
                PageRequest.of(0, 2)
        ))
                .thenReturn(new SliceImpl<>(
                        List.of(newestReport, nextReport),
                        PageRequest.of(0, 2),
                        true
                ));

        AnalysisListResponse response = analysisService.getReports(1L, null, 2);

        assertThat(response.items())
                .extracting("reportId", "imageUrl")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(501L, "/uploads/look.jpg"),
                        org.assertj.core.groups.Tuple.tuple(500L, "/uploads/look.jpg")
                );
        assertThat(response.items().getFirst().tags()).containsExactly("미니멀");
        assertThat(response.nextCursor()).isEqualTo(500L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(2);
    }

    @Test
    void getsOwnedReportWithRecommendationGroups() {
        Member member = member(1L);
        Tag minimal = tag(10L, "미니멀");
        AnalysisReport report = report(501L, member, minimal);
        report.confirmRecommendationInput(List.of(minimal), List.of("출근룩"), 70);
        RecommendationGroupResponse recommendationGroup = new RecommendationGroupResponse(
                ProductCategory.TOP,
                List.of()
        );
        RecommendationResultResponse recommendationResult =
                new RecommendationResultResponse(
                        RecommendationStatus.CURRENT,
                        "SIMILARITY_V1",
                        List.of(recommendationGroup),
                        false,
                        List.of()
        );
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.of(report));
        when(recommendationResultProvider.findFor(report)).thenReturn(recommendationResult);

        AnalysisDetailResponse response = analysisService.getReport(1L, 501L);

        assertThat(response.reportId()).isEqualTo(501L);
        assertThat(response.tags()).containsExactly("미니멀", "출근룩");
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
        assertThat(response.scoreVersion()).isEqualTo("SIMILARITY_V1");
        assertThat(response.recommendationGroups()).containsExactly(recommendationGroup);
    }

    @Test
    void preventsDeletingAnotherMembersReport() {
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisService.deleteReport(2L, 501L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_REPORT_NOT_FOUND);
        verify(analysisReportRepository, never()).delete(any());
    }

    @Test
    void softDeletesOwnedReportWithoutRemovingRelationships() {
        Member member = member(1L);
        AnalysisReport report = report(501L, member);
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.of(report));

        analysisService.deleteReport(1L, 501L);

        assertThat(report.getDeletedAt()).isEqualTo(clock.instant());
        verify(analysisReportRepository, never()).delete(any());
    }

    private Member member(Long id) {
        Member member = Member.create("member@example.com", "주녁", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private Tag tag(Long id, String name) {
        Tag tag = Tag.create(name, TagType.DETAIL);
        ReflectionTestUtils.setField(tag, "id", id);
        return tag;
    }

    private AnalysisReport report(Long id, Member member, Tag... tags) {
        AnalysisReport report = AnalysisReport.create(member, "/uploads/look.jpg", 70);
        ReflectionTestUtils.setField(report, "id", id);
        for (Tag tag : tags) {
            report.addAiSuggestedTag(tag);
        }
        return report;
    }
}
