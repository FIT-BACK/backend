package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.dto.AnalysisCreateResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisDetailResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisListResponse;
import com.fitback.backend.domain.analysis.dto.ConfirmTagsRequest;
import com.fitback.backend.domain.analysis.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.analysis.dto.RecommendationItemResponse;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.entity.ReportTag;
import com.fitback.backend.domain.analysis.entity.ReportTagSource;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
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
    private TagRepository tagRepository;

    @Mock
    private ImageStorage imageStorage;

    @Mock
    private AiTagAnalyzer aiTagAnalyzer;

    @Mock
    private RecommendationResultProvider recommendationResultProvider;

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(
                analysisReportRepository,
                memberRepository,
                tagRepository,
                imageStorage,
                aiTagAnalyzer,
                recommendationResultProvider
        );
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
    void confirmsOnlyExistingTagsAndPreservesAiSource() {
        Member member = member(1L);
        Tag minimal = tag(10L, "미니멀");
        Tag wideFit = tag(20L, "와이드핏");
        Tag beige = tag(30L, "베이지톤");
        AnalysisReport report = report(501L, member, minimal, wideFit);
        when(analysisReportRepository.findByIdAndMemberId(501L, 1L))
                .thenReturn(Optional.of(report));
        when(tagRepository.findAllById(any())).thenReturn(List.of(minimal, beige));
        when(recommendationResultProvider.generateFor(report)).thenReturn(List.of());

        AnalysisDetailResponse response = analysisService.confirmTags(
                1L,
                501L,
                new ConfirmTagsRequest(List.of(10L, 30L), 85)
        );

        assertThat(response.matchPercentage()).isEqualTo(85);
        assertThat(response.tags()).containsExactly("미니멀", "베이지톤");
        assertThat(report.getReportTags())
                .extracting(ReportTag::getTag, ReportTag::getSource, ReportTag::isConfirmed)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(minimal, ReportTagSource.AI, true),
                        org.assertj.core.groups.Tuple.tuple(beige, ReportTagSource.USER, true)
                );
    }

    @Test
    void listsReportsWithCursorMetadata() {
        Member member = member(1L);
        Tag minimal = tag(10L, "미니멀");
        AnalysisReport newestReport = report(501L, member, minimal);
        AnalysisReport nextReport = report(500L, member, minimal);
        when(analysisReportRepository.findByMemberIdOrderByIdDesc(1L, PageRequest.of(0, 2)))
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
        RecommendationGroupResponse recommendationGroup = new RecommendationGroupResponse(
                "상의",
                List.of(new RecommendationItemResponse(
                        1,
                        "https://example.com/item.jpg",
                        "오버핏 셔츠",
                        "무신사",
                        28900,
                        "https://example.com/purchase"
                ))
        );
        when(analysisReportRepository.findByIdAndMemberId(501L, 1L))
                .thenReturn(Optional.of(report));
        when(recommendationResultProvider.findByReportId(501L))
                .thenReturn(List.of(recommendationGroup));

        AnalysisDetailResponse response = analysisService.getReport(1L, 501L);

        assertThat(response.reportId()).isEqualTo(501L);
        assertThat(response.tags()).containsExactly("미니멀");
        assertThat(response.recommendationGroups()).containsExactly(recommendationGroup);
    }

    @Test
    void rejectsConfirmationWhenAnyTagDoesNotExist() {
        Member member = member(1L);
        Tag minimal = tag(10L, "미니멀");
        AnalysisReport report = report(501L, member, minimal);
        when(analysisReportRepository.findByIdAndMemberId(501L, 1L))
                .thenReturn(Optional.of(report));
        when(tagRepository.findAllById(any())).thenReturn(List.of(minimal));

        assertThatThrownBy(() -> analysisService.confirmTags(
                1L,
                501L,
                new ConfirmTagsRequest(List.of(10L, 999L), 70)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.TAG_NOT_FOUND);
        verify(recommendationResultProvider, never()).generateFor(any());
    }

    @Test
    void preventsDeletingAnotherMembersReport() {
        when(analysisReportRepository.findByIdAndMemberId(501L, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisService.deleteReport(2L, 501L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_REPORT_NOT_FOUND);
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
