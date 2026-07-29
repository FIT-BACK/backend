package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.SavedProductRepository;
import com.fitback.backend.domain.product.service.ProductDetailService;
import com.fitback.backend.domain.product.service.ProductResponseMapper;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationItemResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecommendationQueryServiceTest {

    private final AnalysisReportRepository analysisReportRepository =
            mock(AnalysisReportRepository.class);
    private final RecommendedItemRepository recommendedItemRepository =
            mock(RecommendedItemRepository.class);
    private final SavedProductRepository savedProductRepository =
            mock(SavedProductRepository.class);
    private final ProductDetailService productDetailService =
            mock(ProductDetailService.class);
    private final RecommendationQueryService queryService = new RecommendationQueryService(
            analysisReportRepository,
            recommendedItemRepository,
            mock(ProductResponseMapper.class),
            productDetailService,
            savedProductRepository
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

    @Test
    void rejectsRecommendationLookupForUnownedReport() {
        when(analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(501L, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.findByReportId(1L, 501L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_REPORT_NOT_FOUND);
    }

    @Test
    void hydratesIdentityOnlyRecommendationWithLiveProductDetail() {
        AnalysisReport report = mock(AnalysisReport.class);
        Member member = mock(Member.class);
        RecommendedItem item = mock(RecommendedItem.class);
        Product product = mock(Product.class);
        ProductDetailResponse detail = new ProductDetailResponse(
                42L,
                "https://cdn.example/product.jpg",
                "Live Product",
                null,
                "Live Store",
                ProductCategory.TOP,
                null,
                "https://merchant.example/checkout",
                null,
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE,
                List.of(),
                false
        );
        when(report.getId()).thenReturn(501L);
        when(report.getMember()).thenReturn(member);
        when(member.getId()).thenReturn(1L);
        when(report.getRecommendationGeneratedAt())
                .thenReturn(Instant.parse("2026-07-25T00:00:00Z"));
        when(report.getResultInputRevision()).thenReturn(1);
        when(report.getResultScoreVersion()).thenReturn("SIMILARITY_V1");
        when(report.hasRecommendationInputRevision(1)).thenReturn(true);
        when(item.getProduct()).thenReturn(product);
        when(item.getCategory()).thenReturn(ProductCategory.TOP);
        when(item.getRankNo()).thenReturn(1);
        when(item.getSimilarityScore()).thenReturn(new BigDecimal("90.00"));
        when(item.getFinalScore()).thenReturn(new BigDecimal("90.00"));
        when(item.getReasonCodeList()).thenReturn(List.of("CATEGORY_MATCH"));
        when(product.getId()).thenReturn(42L);
        when(product.getStorageMode()).thenReturn(ProductStorageMode.IDENTITY_ONLY);
        when(recommendedItemRepository.findByReportIdOrderByCategoryAscRankNoAsc(501L))
                .thenReturn(List.of(item));
        when(savedProductRepository.findSavedProductIds(1L, List.of(42L)))
                .thenReturn(List.of());
        when(productDetailService.getDetail(42L)).thenReturn(detail);

        RecommendationResultResponse response = queryService.findFor(report);

        RecommendationItemResponse responseItem = response.recommendationGroups().stream()
                .filter(group -> group.category() == ProductCategory.TOP)
                .findFirst()
                .orElseThrow()
                .items()
                .getFirst();
        assertThat(responseItem.name()).isEqualTo("Live Product");
        assertThat(responseItem.imageUrl()).isEqualTo("https://cdn.example/product.jpg");
        assertThat(responseItem.purchaseUrl())
                .isEqualTo("https://merchant.example/checkout");
        assertThat(response.partial()).isFalse();
        verify(productDetailService).getDetail(42L);
    }
}
