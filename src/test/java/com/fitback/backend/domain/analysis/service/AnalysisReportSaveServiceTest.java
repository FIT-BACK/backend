package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.dto.AnalysisReportSaveRequest;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.entity.SavedAnalysisItem;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.closet.repository.SavedAnalysisItemRepository;
import com.fitback.backend.domain.image.service.ImageUploadService;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderIdentityType;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnalysisReportSaveServiceTest {

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    @Mock
    private ClosetSaveRepository closetSaveRepository;

    @Mock
    private SavedAnalysisItemRepository savedAnalysisItemRepository;

    @Mock
    private RecommendedItemRepository recommendedItemRepository;

    @Mock
    private ImageUploadService imageUploadService;

    @Mock
    private EntityManager entityManager;

    private AnalysisReportSaveService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisReportSaveService(
                analysisReportRepository,
                closetSaveRepository,
                savedAnalysisItemRepository,
                recommendedItemRepository,
                imageUploadService,
                entityManager
        );
    }

    @Test
    void savesOneCurrentRecommendationPerAvailableCategory() {
        Member member = member(1L);
        AnalysisReport report = currentReport(501L, member);
        Product top = product(101L, ProductCategory.TOP, "셔츠", new BigDecimal("28900"));
        Product bottom = product(
                202L,
                ProductCategory.BOTTOM,
                "슬랙스",
                new BigDecimal("34900")
        );
        RecommendedItem topItem = recommendation(report, top, ProductCategory.TOP, 1);
        RecommendedItem bottomItem = recommendation(
                report,
                bottom,
                ProductCategory.BOTTOM,
                1
        );
        when(analysisReportRepository.findOwnedReportForSave(501L, 1L))
                .thenReturn(Optional.of(report));
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        )).thenReturn(Optional.empty());
        when(recommendedItemRepository.findByReportIdOrderByCategoryAscRankNoAsc(501L))
                .thenReturn(List.of(topItem, bottomItem));
        when(closetSaveRepository.saveAndFlush(any(ClosetSave.class)))
                .thenAnswer(invocation -> {
                    ClosetSave save = invocation.getArgument(0);
                    ReflectionTestUtils.setField(save, "id", 900L);
                    ReflectionTestUtils.setField(
                            save,
                            "createdAt",
                            LocalDateTime.parse("2026-07-26T09:00:00")
                    );
                    return save;
                });
        when(savedAnalysisItemRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AnalysisReportSaveService.SaveOutcome outcome = service.save(
                1L,
                501L,
                request(
                        selected(ProductCategory.TOP, 101L),
                        selected(ProductCategory.BOTTOM, 202L)
                )
        );

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response().saved()).isTrue();
        assertThat(outcome.response().selectedItems())
                .extracting("category", "productId", "name")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ProductCategory.TOP,
                                101L,
                                "셔츠"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                ProductCategory.BOTTOM,
                                202L,
                                "슬랙스"
                        )
                );
        assertThat(outcome.response().selectedItems().getFirst().price().amount())
                .isEqualByComparingTo("28900");
        verify(entityManager).refresh(any(ClosetSave.class));
    }

    @Test
    void rejectsSelectionWhenAnAvailableCategoryIsMissing() {
        Member member = member(1L);
        AnalysisReport report = currentReport(501L, member);
        Product top = product(101L, ProductCategory.TOP, "셔츠", new BigDecimal("28900"));
        Product bottom = product(
                202L,
                ProductCategory.BOTTOM,
                "슬랙스",
                new BigDecimal("34900")
        );
        when(analysisReportRepository.findOwnedReportForSave(501L, 1L))
                .thenReturn(Optional.of(report));
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        )).thenReturn(Optional.empty());
        when(recommendedItemRepository.findByReportIdOrderByCategoryAscRankNoAsc(501L))
                .thenReturn(List.of(
                        recommendation(report, top, ProductCategory.TOP, 1),
                        recommendation(report, bottom, ProductCategory.BOTTOM, 1)
                ));

        assertThatThrownBy(() -> service.save(
                1L,
                501L,
                request(selected(ProductCategory.TOP, 101L))
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_SELECTION_INVALID);
        verify(closetSaveRepository, never()).saveAndFlush(any());
    }

    @Test
    void returnsExistingSaveWithoutReplacingSnapshots() {
        Member member = member(1L);
        AnalysisReport report = currentReport(501L, member);
        ClosetSave save = ClosetSave.create(
                member,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        );
        ReflectionTestUtils.setField(save, "id", 900L);
        ReflectionTestUtils.setField(
                save,
                "createdAt",
                LocalDateTime.parse("2026-07-26T09:00:00")
        );
        when(analysisReportRepository.findOwnedReportForSave(501L, 1L))
                .thenReturn(Optional.of(report));
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        )).thenReturn(Optional.of(save));
        when(savedAnalysisItemRepository.findByClosetSaveIdOrderByCategoryAsc(900L))
                .thenReturn(List.of());

        AnalysisReportSaveService.SaveOutcome outcome = service.save(
                1L,
                501L,
                request(selected(ProductCategory.TOP, 101L))
        );

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.response().savedAt())
                .isEqualTo(LocalDateTime.parse("2026-07-26T09:00:00"));
        verify(recommendedItemRepository, never())
                .findByReportIdOrderByCategoryAscRankNoAsc(any());
        verify(closetSaveRepository, never()).saveAndFlush(any());
    }

    private AnalysisReportSaveRequest request(
            AnalysisReportSaveRequest.SelectedItem... selections
    ) {
        return new AnalysisReportSaveRequest(List.of(selections));
    }

    private AnalysisReportSaveRequest.SelectedItem selected(
            ProductCategory category,
            Long productId
    ) {
        return new AnalysisReportSaveRequest.SelectedItem(category, productId);
    }

    private Member member(Long id) {
        Member member = Member.create(
                "member@example.com",
                "사용자",
                "password123",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    // 기본 태그와 직접 입력한 커스텀 태그를 한 목록으로 반환
    @Test
    void findClosetViewsReturnsImageUrlWithKnownAndCustomTags() {
        Member member = member(1L);
        AnalysisReport report = currentReport(33L, member);
        Tag minimalTag = Tag.create("미니멀", TagType.DETAIL);
        ReflectionTestUtils.setField(minimalTag, "id", 10L);
        report.addAiSuggestedTag(minimalTag);
        report.confirmRecommendationInput(List.of(minimalTag), List.of("와이드핏"), 70);
        when(analysisReportRepository.findByIdInAndMemberIdAndDeletedAtIsNull(List.of(33L), 1L))
                .thenReturn(List.of(report));

        Map<Long, AnalysisReportSaveService.ClosetReportView> views =
                service.findClosetViews(List.of(33L), 1L);

        assertThat(views).containsOnlyKeys(33L);
        assertThat(views.get(33L).thumbnailUrl()).isEqualTo("https://example.com/original.jpg");
        assertThat(views.get(33L).tags()).containsExactly("미니멀", "와이드핏");
    }

    // 원본 이미지가 없는 레거시 리포트는 imageUrl 컬럼으로 폴백
    @Test
    void findClosetViewsFallsBackToImageUrlWhenOriginalImageMissing() {
        AnalysisReport report = currentReport(33L, member(1L));
        when(analysisReportRepository.findByIdInAndMemberIdAndDeletedAtIsNull(List.of(33L), 1L))
                .thenReturn(List.of(report));

        Map<Long, AnalysisReportSaveService.ClosetReportView> views =
                service.findClosetViews(List.of(33L), 1L);

        assertThat(views.get(33L).thumbnailUrl()).isEqualTo("https://example.com/original.jpg");
        verify(imageUploadService, never()).createReadUrl(any());
    }

    // 삭제된 리포트는 전용 목록과 달리 예외 없이 제외
    @Test
    void findClosetViewsExcludesDeletedReportsWithoutException() {
        when(analysisReportRepository.findByIdInAndMemberIdAndDeletedAtIsNull(List.of(33L), 1L))
                .thenReturn(List.of());

        Map<Long, AnalysisReportSaveService.ClosetReportView> views =
                service.findClosetViews(List.of(33L), 1L);

        assertThat(views).isEmpty();
    }

    @Test
    void findClosetViewsSkipsQueryWhenNoReportSaved() {
        Map<Long, AnalysisReportSaveService.ClosetReportView> views =
                service.findClosetViews(List.of(), 1L);

        assertThat(views).isEmpty();
        verify(analysisReportRepository, never())
                .findByIdInAndMemberIdAndDeletedAtIsNull(any(), any());
    }

    private AnalysisReport currentReport(Long id, Member member) {
        AnalysisReport report = AnalysisReport.create(
                member,
                "https://example.com/original.jpg",
                70
        );
        ReflectionTestUtils.setField(report, "id", id);
        report.markRecommendationGenerated(
                report.getRecommendationInputRevision(),
                "SIMILARITY_V1",
                Instant.parse("2026-07-26T00:00:00Z")
        );
        return report;
    }

    private Product product(
            Long id,
            ProductCategory category,
            String name,
            BigDecimal price
    ) {
        Product product = Product.createProviderProduct(
                "fixture",
                ProviderIdentityType.PROVIDER_KEY,
                "identity-" + id,
                "materialization-" + id,
                "external-" + id,
                null,
                "merchant-" + id,
                ProductStorageMode.SNAPSHOT,
                name,
                null,
                "판매처",
                category,
                "https://example.com/" + id + ".jpg",
                null,
                price,
                null,
                "KRW",
                Instant.parse("2026-07-26T00:00:00Z"),
                "https://example.com/products/" + id,
                null,
                ProductAvailability.AVAILABLE,
                Instant.parse("2026-07-27T00:00:00Z")
        );
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private RecommendedItem recommendation(
            AnalysisReport report,
            Product product,
            ProductCategory category,
            int rank
    ) {
        return RecommendedItem.create(
                report,
                product,
                report.getRecommendationInputRevision(),
                rank,
                category,
                new BigDecimal("90.00"),
                new BigDecimal("88.00"),
                "SIMILARITY_V1",
                List.of("HIGH_SIMILARITY")
        );
    }
}
