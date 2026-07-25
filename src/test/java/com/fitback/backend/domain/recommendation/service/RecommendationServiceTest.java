package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.product.service.ProductCandidateMapper;
import com.fitback.backend.domain.product.service.ProductMaterializationService;
import com.fitback.backend.domain.product.service.ProductMaterializationService.RecommendationMaterializationResult;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.domain.recommendation.dto.RecommendationCreateResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.recommendation.service.model.RecommendationSelection;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private RecommendationInputReader inputReader;

    @Mock
    private ProductCatalogPort productCatalogPort;

    @Mock
    private ProductCandidateMapper candidateMapper;

    @Mock
    private ProductMaterializationService materializationService;

    @Mock
    private RecommendationSetWriter setWriter;

    @Mock
    private RecommendationQueryService queryService;

    private RecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        recommendationService = new RecommendationService(
                inputReader,
                productCatalogPort,
                candidateMapper,
                materializationService,
                new RecommendationScorer(),
                setWriter,
                queryService
        );
    }

    @Test
    void deduplicatesAndSelectsTopFivePerCategoryDeterministically() {
        RecommendationInputSnapshot input = input();
        List<ExternalProductCandidate> candidates = List.of(
                candidate(1, "0.10", true),
                candidate(2, "0.70", true),
                candidate(3, "0.60", true),
                candidate(4, "0.50", true),
                candidate(5, "0.40", true),
                candidate(6, "0.30", true),
                candidate(2, "0.70", true)
        );
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(candidates, null));
        when(candidateMapper.category(any())).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(any()))
                .thenAnswer(invocation -> {
                    ExternalProductCandidate candidate = invocation.getArgument(0);
                    return new RecommendationMaterializationResult(
                            Long.parseLong(candidate.providerRef().externalProductId()),
                            true
                    );
                });
        when(queryService.findByReportId(501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("SIMILARITY_V1"),
                selectionsCaptor.capture()
        );
        assertThat(selectionsCaptor.getValue())
                .extracting(
                        RecommendationSelection::productId,
                        RecommendationSelection::rankNo
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, 1),
                        org.assertj.core.groups.Tuple.tuple(3L, 2),
                        org.assertj.core.groups.Tuple.tuple(4L, 3),
                        org.assertj.core.groups.Tuple.tuple(5L, 4),
                        org.assertj.core.groups.Tuple.tuple(6L, 5)
                );
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
        assertThat(response.partial()).isFalse();
    }

    @Test
    void skipsUnsupportedCandidateAndReturnsPartialWarning() {
        RecommendationInputSnapshot input = input();
        ExternalProductCandidate stable = candidate(1, "0.90", true);
        ExternalProductCandidate unstable = candidate(2, "0.80", false);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(stable, unstable), null));
        when(candidateMapper.category(any())).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(stable))
                .thenReturn(new RecommendationMaterializationResult(1L, true));
        doThrow(new BusinessException(ErrorCode.PRODUCT_REFERENCE_UNSUPPORTED))
                .when(materializationService)
                .materializeForRecommendation(unstable);
        when(queryService.findByReportId(501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        assertThat(response.partial()).isTrue();
        assertThat(response.warnings()).containsExactly("MATERIALIZATION_SKIPPED");
    }

    @Test
    void preservesCurrentSetWhenEveryProviderSearchFails() {
        when(inputReader.read(1L, 501L)).thenReturn(input());
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenThrow(new ProductProviderException(
                        "fixture",
                        ProductProviderFailure.TIMEOUT
                ));

        assertThatThrownBy(() -> recommendationService.generate(1L, 501L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE);
        verify(setWriter, never()).replaceCurrentSet(any(), any(), any());
    }

    @Test
    void recordsSuccessfulEmptySetInsteadOfLeavingNotGeneratedState() {
        RecommendationInputSnapshot input = input();
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(), null));
        when(queryService.findByReportId(501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        verify(setWriter).replaceCurrentSet(input, "SIMILARITY_V1", List.of());
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
    }

    private static RecommendationInputSnapshot input() {
        return new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                List.of(new TagInput(10L, "Fixture"))
        );
    }

    private static RecommendationResultResponse currentResult() {
        List<RecommendationGroupResponse> groups = Arrays.stream(ProductCategory.values())
                .map(category -> new RecommendationGroupResponse(category, List.of()))
                .toList();
        return new RecommendationResultResponse(
                RecommendationStatus.CURRENT,
                "SIMILARITY_V1",
                groups,
                false,
                List.of()
        );
    }

    private static ExternalProductCandidate candidate(
            int id,
            String score,
            boolean stable
    ) {
        ProviderProductRef providerRef = stable
                ? ProviderProductRef.stable("fixture", Integer.toString(id), null, "store")
                : ProviderProductRef.unstable("fixture");
        return new ExternalProductCandidate(
                providerRef,
                "Fixture Product " + id,
                null,
                "tops/shirts",
                null,
                null,
                new BigDecimal(score),
                Instant.parse("2026-07-25T00:00:00Z")
        );
    }
}
