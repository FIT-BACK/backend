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
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.service.ImageUploadService;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.ProductDetailService;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
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
    private ProductDetailService productDetailService;

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
                productDetailService,
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
    void hydratesLiveProductDetailWhenStorageModeIsIdentityOnly() {
        // IDENTITY_ONLY 상품은 Product 엔티티에 표시 데이터(이미지/이름/가격)를 저장하지
        // 않고 응답 시점에만 live hydrate한다 — 저장 스냅샷도 raw 엔티티 값이 아니라
        // productDetailService가 돌려주는 live 데이터를 써야 "상품 정보 없음"으로
        // 영구히 굳지 않는다.
        Member member = member(1L);
        AnalysisReport report = currentReport(501L, member);
        Product outer = identityOnlyProduct(303L, ProductCategory.OUTER);
        RecommendedItem outerItem = recommendation(report, outer, ProductCategory.OUTER, 1);

        when(analysisReportRepository.findOwnedReportForSave(501L, 1L))
                .thenReturn(Optional.of(report));
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        )).thenReturn(Optional.empty());
        when(recommendedItemRepository.findByReportIdOrderByCategoryAscRankNoAsc(501L))
                .thenReturn(List.of(outerItem));
        when(closetSaveRepository.saveAndFlush(any(ClosetSave.class)))
                .thenAnswer(invocation -> {
                    ClosetSave save = invocation.getArgument(0);
                    ReflectionTestUtils.setField(save, "id", 902L);
                    ReflectionTestUtils.setField(
                            save,
                            "createdAt",
                            LocalDateTime.parse("2026-07-26T09:00:00")
                    );
                    return save;
                });
        when(savedAnalysisItemRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productDetailService.getDetail(303L)).thenReturn(new ProductDetailResponse(
                303L,
                "https://cdn.example.com/live/303.jpg",
                "라이브로 조회된 코트",
                null,
                "라이브 판매처",
                ProductCategory.OUTER,
                new ProductPriceResponse(
                        new BigDecimal("59000"),
                        "KRW",
                        ProductPriceResponse.Type.CURRENT,
                        Instant.parse("2026-08-12T00:00:00Z")
                ),
                "https://example.com/products/303",
                null,
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE,
                List.of(),
                false
        ));

        AnalysisReportSaveService.SaveOutcome outcome = service.save(
                1L,
                501L,
                request(selected(ProductCategory.OUTER, 303L))
        );

        var saved = outcome.response().selectedItems().getFirst();
        assertThat(saved.imageUrl()).isEqualTo("https://cdn.example.com/live/303.jpg");
        assertThat(saved.name()).isEqualTo("라이브로 조회된 코트");
        assertThat(saved.sellerName()).isEqualTo("라이브 판매처");
        assertThat(saved.price().amount()).isEqualByComparingTo("59000");
    }

    @Test
    void fallsBackToEntityValuesWhenLiveProductDetailLookupFails() {
        // 저장 시점에 live 조회(외부 API)가 레이트리밋 등으로 실패해도 저장 자체는
        // 막지 않아야 한다 — 실패하면 기존처럼 null로 폴백(엔티티 원본 값 그대로).
        Member member = member(1L);
        AnalysisReport report = currentReport(501L, member);
        Product outer = identityOnlyProduct(304L, ProductCategory.OUTER);
        RecommendedItem outerItem = recommendation(report, outer, ProductCategory.OUTER, 1);

        when(analysisReportRepository.findOwnedReportForSave(501L, 1L))
                .thenReturn(Optional.of(report));
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        )).thenReturn(Optional.empty());
        when(recommendedItemRepository.findByReportIdOrderByCategoryAscRankNoAsc(501L))
                .thenReturn(List.of(outerItem));
        when(closetSaveRepository.saveAndFlush(any(ClosetSave.class)))
                .thenAnswer(invocation -> {
                    ClosetSave save = invocation.getArgument(0);
                    ReflectionTestUtils.setField(save, "id", 903L);
                    ReflectionTestUtils.setField(
                            save,
                            "createdAt",
                            LocalDateTime.parse("2026-07-26T09:00:00")
                    );
                    return save;
                });
        when(savedAnalysisItemRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productDetailService.getDetail(304L))
                .thenThrow(new BusinessException(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE));

        AnalysisReportSaveService.SaveOutcome outcome = service.save(
                1L,
                501L,
                request(selected(ProductCategory.OUTER, 304L))
        );

        var saved = outcome.response().selectedItems().getFirst();
        assertThat(saved.imageUrl()).isNull();
        assertThat(saved.name()).isNull();
    }

    @Test
    void savesPartialSelectionWhenNotEveryAvailableCategoryIsChosen() {
        // 마음에 드는 상품 1개만 골라도 저장할 수 있어야 한다 — 추천에 잡힌 카테고리를
        // 전부 채우도록 강제하지 않는다.
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
        when(closetSaveRepository.saveAndFlush(any(ClosetSave.class)))
                .thenAnswer(invocation -> {
                    ClosetSave save = invocation.getArgument(0);
                    ReflectionTestUtils.setField(save, "id", 901L);
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
                request(selected(ProductCategory.TOP, 101L))
        );

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response().selectedItems())
                .extracting("category", "productId")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ProductCategory.TOP, 101L));
    }

    @Test
    void rejectsSelectionForCategoryNotInRecommendationResults() {
        // 추천 결과에 아예 없는 카테고리(SHOES)를 골랐다고 우기면 여전히 거부해야 한다.
        Member member = member(1L);
        AnalysisReport report = currentReport(501L, member);
        Product top = product(101L, ProductCategory.TOP, "셔츠", new BigDecimal("28900"));
        when(analysisReportRepository.findOwnedReportForSave(501L, 1L))
                .thenReturn(Optional.of(report));
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        )).thenReturn(Optional.empty());
        when(recommendedItemRepository.findByReportIdOrderByCategoryAscRankNoAsc(501L))
                .thenReturn(List.of(recommendation(report, top, ProductCategory.TOP, 1)));

        assertThatThrownBy(() -> service.save(
                1L,
                501L,
                request(selected(ProductCategory.SHOES, 999L))
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_SELECTION_INVALID);
        verify(closetSaveRepository, never()).saveAndFlush(any());
    }

    @Test
    void preservesRequestOrderRegardlessOfCategoryEnumDeclarationOrder() {
        // ProductCategory enum 선언 순서는 TOP이 BOTTOM보다 앞이지만, 요청은 BOTTOM을
        // 먼저 보낸다 — 응답 순서가 enum 순서(TOP, BOTTOM)가 아니라 요청 순서(BOTTOM, TOP)를
        // 따라야 한다(EnumMap 순회에 의존하면 이 순서가 깨진다).
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
        when(closetSaveRepository.saveAndFlush(any(ClosetSave.class)))
                .thenAnswer(invocation -> {
                    ClosetSave save = invocation.getArgument(0);
                    ReflectionTestUtils.setField(save, "id", 902L);
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
                        selected(ProductCategory.BOTTOM, 202L),
                        selected(ProductCategory.TOP, 101L)
                )
        );

        assertThat(outcome.response().selectedItems())
                .extracting("category", "productId")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ProductCategory.BOTTOM, 202L),
                        org.assertj.core.groups.Tuple.tuple(ProductCategory.TOP, 101L)
                );
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
        when(savedAnalysisItemRepository.findByClosetSaveIdOrderByIdAsc(900L))
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

    @Test
    void reReadingAnExistingSavePreservesOriginalInsertionOrderNotCategoryOrder() {
        // 이미 저장된 걸 재조회할 때도(재저장 시도 시 기존 저장 그대로 반환하는 경로),
        // 최초 저장 당시 순서(BOTTOM 먼저, TOP 나중)를 그대로 유지해야 한다 — enum 선언
        // 순서(TOP이 BOTTOM보다 앞)로 재조합되면 안 된다.
        Member member = member(1L);
        AnalysisReport report = currentReport(501L, member);
        ClosetSave save = ClosetSave.create(member, ClosetTargetType.ANALYSIS_REPORT, 501L);
        ReflectionTestUtils.setField(save, "id", 900L);
        ReflectionTestUtils.setField(
                save,
                "createdAt",
                LocalDateTime.parse("2026-07-26T09:00:00")
        );
        Product bottom = product(202L, ProductCategory.BOTTOM, "슬랙스", new BigDecimal("34900"));
        Product top = product(101L, ProductCategory.TOP, "셔츠", new BigDecimal("28900"));
        SavedAnalysisItem bottomItem = SavedAnalysisItem.from(
                save,
                recommendation(report, bottom, ProductCategory.BOTTOM, 1)
        );
        SavedAnalysisItem topItem = SavedAnalysisItem.from(
                save,
                recommendation(report, top, ProductCategory.TOP, 1)
        );

        when(analysisReportRepository.findOwnedReportForSave(501L, 1L))
                .thenReturn(Optional.of(report));
        when(closetSaveRepository.findByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.ANALYSIS_REPORT,
                501L
        )).thenReturn(Optional.of(save));
        // BOTTOM이 먼저 저장됐던 순서 그대로 반환(=ID/삽입 순서 기준) — 카테고리 오름차순이면 TOP이 먼저 와야 함
        when(savedAnalysisItemRepository.findByClosetSaveIdOrderByIdAsc(900L))
                .thenReturn(List.of(bottomItem, topItem));

        AnalysisReportSaveService.SaveOutcome outcome = service.save(
                1L,
                501L,
                request(selected(ProductCategory.TOP, 101L))
        );

        assertThat(outcome.response().selectedItems())
                .extracting("category")
                .containsExactly(ProductCategory.BOTTOM, ProductCategory.TOP);
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
    void findClosetViewsReturnsSignedImageUrlWithKnownAndCustomTags() {
        Member member = member(1L);
        Image originalImage = analysisImage(member);
        AnalysisReport report = AnalysisReport.create(member, originalImage, 70);
        ReflectionTestUtils.setField(report, "id", 33L);
        Tag minimalTag = Tag.create("미니멀", TagType.DETAIL);
        ReflectionTestUtils.setField(minimalTag, "id", 10L);
        report.addAiSuggestedTag(minimalTag);
        report.confirmRecommendationInput(List.of(minimalTag), List.of("와이드핏"), 70);
        when(analysisReportRepository.findByIdInAndMemberIdAndDeletedAtIsNull(List.of(33L), 1L))
                .thenReturn(List.of(report));
        when(imageUploadService.createReadUrl(originalImage))
                .thenReturn("https://cdn.fitback.app/analyses/signed.jpg");

        Map<Long, AnalysisReportSaveService.ClosetReportView> views =
                service.findClosetViews(List.of(33L), 1L);

        assertThat(views).containsOnlyKeys(33L);
        assertThat(views.get(33L).thumbnailUrl())
                .isEqualTo("https://cdn.fitback.app/analyses/signed.jpg");
        assertThat(views.get(33L).tags()).containsExactly("미니멀", "와이드핏");
        verify(imageUploadService).createReadUrl(originalImage);
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

    private Image analysisImage(Member owner) {
        return Image.createPending(
                "analysis-image-id",
                owner,
                "images/analysis/1/2026/08/analysis-image-id.jpg",
                ImagePurpose.ANALYSIS,
                "image/jpeg",
                1024L,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-08-01T00:00:00Z")
        );
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

    // IDENTITY_ONLY 상품은 실제로 표시 데이터(이미지/이름/가격 등)를 엔티티에 저장하지
    // 않는 게 정상 상태라, 테스트에서도 그 필드들을 전부 null로 둬서 실제 상황을 그대로
    // 재현한다.
    private Product identityOnlyProduct(Long id, ProductCategory category) {
        Product product = Product.createProviderProduct(
                "fixture",
                ProviderIdentityType.PROVIDER_KEY,
                "identity-" + id,
                "materialization-" + id,
                "external-" + id,
                null,
                "merchant-" + id,
                ProductStorageMode.IDENTITY_ONLY,
                null,
                null,
                null,
                category,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ProductAvailability.AVAILABLE,
                null
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
