package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.fitback.backend.domain.recommendation.dto.RecommendationGenerateRequest;
import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.recommendation.service.model.RecommendationSelection;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
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
    private RecommendationInputCommandService inputCommandService;

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
        recommendationService = recommendationService(64);
    }

    private RecommendationService recommendationService(int candidateLimit) {
        return new RecommendationService(
                inputReader,
                inputCommandService,
                productCatalogPort,
                candidateMapper,
                materializationService,
                new ImageComparisonCandidateSelector(
                        new RoundRobinImageComparisonCandidateOrderingPolicy(),
                        candidateLimit
                ),
                new RecommendationScorer(),
                setWriter,
                queryService
        );
    }

    @Test
    void deduplicatesAndSelectsTopTenPerCategoryDeterministically() {
        RecommendationInputSnapshot input = input();
        List<ExternalProductCandidate> candidates = List.of(
                candidate(1, "0.01", true),
                candidate(2, "0.99", true),
                candidate(3, "0.90", true),
                candidate(4, "0.80", true),
                candidate(5, "0.70", true),
                candidate(6, "0.60", true),
                candidate(7, "0.50", true),
                candidate(8, "0.40", true),
                candidate(9, "0.30", true),
                candidate(10, "0.20", true),
                candidate(11, "0.10", true),
                candidate(2, "0.99", true)
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
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("TAG_MATCH_RATIO_V1"),
                selectionsCaptor.capture()
        );
        assertThat(selectionsCaptor.getValue())
                .extracting(
                        RecommendationSelection::productId,
                        RecommendationSelection::rankNo
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 1),
                        org.assertj.core.groups.Tuple.tuple(10L, 2),
                        org.assertj.core.groups.Tuple.tuple(11L, 3),
                        org.assertj.core.groups.Tuple.tuple(2L, 4),
                        org.assertj.core.groups.Tuple.tuple(3L, 5),
                        org.assertj.core.groups.Tuple.tuple(4L, 6),
                        org.assertj.core.groups.Tuple.tuple(5L, 7),
                        org.assertj.core.groups.Tuple.tuple(6L, 8),
                        org.assertj.core.groups.Tuple.tuple(7L, 9),
                        org.assertj.core.groups.Tuple.tuple(8L, 10)
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
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        verify(materializationService, never()).materializeForRecommendation(unstable);
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
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        verify(setWriter).replaceCurrentSet(input, "TAG_MATCH_RATIO_V1", List.of());
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
    }

    @Test
    void usesCustomTagForCandidateSearchAndScoring() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                List.of(),
                List.of("Fixture")
        );
        ExternalProductCandidate candidate = candidate(1, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "Fixture",
                null,
                null,
                20
        ))).thenReturn(new ProductSearchResult(List.of(candidate), null));
        when(candidateMapper.category(candidate)).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(candidate))
                .thenReturn(new RecommendationMaterializationResult(1L, true));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        recommendationService.generate(1L, 501L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("TAG_MATCH_RATIO_V1"),
                selectionsCaptor.capture()
        );
        assertThat(selectionsCaptor.getValue()).singleElement().satisfies(selection -> {
            assertThat(selection.similarityScore()).isEqualByComparingTo("100.00");
            assertThat(selection.reasonCodes()).containsExactly("NO_SCORABLE_TAGS");
        });
    }

    @Test
    void excludesStyleTagFromSearchAndScoring() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                List.of(
                        new TagInput(10L, "Fixture", TagType.STYLE),
                        new TagInput(20L, "unmatched", TagType.DETAIL)
                ),
                List.of()
        );
        ExternalProductCandidate candidate = candidate(1, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "unmatched",
                null,
                null,
                20
        ))).thenReturn(new ProductSearchResult(List.of(candidate), null));
        when(candidateMapper.category(candidate)).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(candidate))
                .thenReturn(new RecommendationMaterializationResult(1L, true));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        recommendationService.generate(1L, 501L);

        verify(productCatalogPort, never()).search(new ProductSearchQuery(
                "Fixture",
                null,
                null,
                20
        ));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("TAG_MATCH_RATIO_V1"),
                selectionsCaptor.capture()
        );
        assertThat(selectionsCaptor.getValue()).singleElement().satisfies(selection -> {
            assertThat(selection.similarityScore()).isEqualByComparingTo("0.00");
            assertThat(selection.reasonCodes()).containsExactly("NO_ATTRIBUTE_MATCH");
        });
    }

    @Test
    void searchesNonStyleAndCustomTagsInInputOrder() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                List.of(
                        new TagInput(10L, "실루엣", TagType.SILHOUETTE),
                        new TagInput(20L, "색상", TagType.COLOR),
                        new TagInput(30L, "스타일", TagType.STYLE),
                        new TagInput(40L, "디테일", TagType.DETAIL),
                        new TagInput(50L, "소재", TagType.MATERIAL)
                ),
                List.of("사용자 태그")
        );
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(), null));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        recommendationService.generate(1L, 501L);

        ArgumentCaptor<ProductSearchQuery> queryCaptor =
                ArgumentCaptor.forClass(ProductSearchQuery.class);
        verify(productCatalogPort, org.mockito.Mockito.times(5)).search(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues())
                .extracting(ProductSearchQuery::keyword)
                .containsExactly("실루엣", "색상", "디테일", "소재", "사용자 태그");
    }

    @Test
    void selectsCandidatesAcrossSearchResultsBeforeScoring() {
        recommendationService = recommendationService(2);
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                List.of(
                        new TagInput(10L, "first", TagType.DETAIL),
                        new TagInput(20L, "second", TagType.COLOR)
                ),
                List.of()
        );
        ExternalProductCandidate firstRankFromFirstSearch = candidate(1, null, true);
        ExternalProductCandidate secondRankFromFirstSearch = candidate(2, null, true);
        ExternalProductCandidate firstRankFromSecondSearch = candidate(3, null, true);
        ExternalProductCandidate secondRankFromSecondSearch = candidate(4, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "first",
                null,
                null,
                20
        ))).thenReturn(new ProductSearchResult(
                List.of(firstRankFromFirstSearch, secondRankFromFirstSearch),
                null
        ));
        when(productCatalogPort.search(new ProductSearchQuery(
                "second",
                null,
                null,
                20
        ))).thenReturn(new ProductSearchResult(
                List.of(firstRankFromSecondSearch, secondRankFromSecondSearch),
                null
        ));
        when(candidateMapper.category(any())).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(any()))
                .thenAnswer(invocation -> {
                    ExternalProductCandidate candidate = invocation.getArgument(0);
                    return new RecommendationMaterializationResult(
                            Long.parseLong(candidate.providerRef().externalProductId()),
                            true
                    );
                });
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        recommendationService.generate(1L, 501L);

        verify(materializationService).materializeForRecommendation(
                firstRankFromFirstSearch
        );
        verify(materializationService).materializeForRecommendation(
                firstRankFromSecondSearch
        );
        verify(materializationService, never()).materializeForRecommendation(
                secondRankFromFirstSearch
        );
        verify(materializationService, never()).materializeForRecommendation(
                secondRankFromSecondSearch
        );
    }

    @Test
    void recordsEmptySetWithoutProviderCallWhenOnlyStyleTagsExist() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                List.of(new TagInput(10L, "스타일", TagType.STYLE)),
                List.of()
        );
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        verify(productCatalogPort, never()).search(any(ProductSearchQuery.class));
        verify(setWriter).replaceCurrentSet(input, "TAG_MATCH_RATIO_V1", List.of());
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
    }

    @Test
    void recordsCurrentEmptySetWhenEveryCandidateIsBelowThreshold() {
        RecommendationGenerateRequest request = new RecommendationGenerateRequest(
                List.of(10L, 20L),
                List.of(),
                100
        );
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                2,
                100,
                List.of(
                        new TagInput(10L, "Fixture", TagType.DETAIL),
                        new TagInput(20L, "Unmatched", TagType.COLOR)
                )
        );
        when(inputCommandService.confirmAndRead(1L, 501L, request)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(
                        List.of(candidate(1, "0.90", true)),
                        null
                ));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(
                1L,
                501L,
                request
        );

        verify(materializationService, never()).materializeForRecommendation(any());
        verify(setWriter).replaceCurrentSet(
                input,
                "TAG_MATCH_RATIO_THRESHOLD_V1",
                List.of()
        );
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
        assertThat(response.scoreVersion()).isEqualTo("TAG_MATCH_RATIO_THRESHOLD_V1");
    }

    @Test
    void thresholdOneHundredIncludesOnlyPerfectScoreCandidate() {
        RecommendationGenerateRequest request = new RecommendationGenerateRequest(
                List.of(10L, 20L),
                List.of(),
                100
        );
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                2,
                100,
                List.of(
                        new TagInput(10L, "Fixture", TagType.DETAIL),
                        new TagInput(20L, "Perfect", TagType.COLOR)
                )
        );
        ExternalProductCandidate perfect = candidate(
                1,
                "0.01",
                true,
                "Fixture Perfect Product"
        );
        ExternalProductCandidate below = candidate(
                2,
                "1.00",
                true,
                "Fixture Product"
        );
        when(inputCommandService.confirmAndRead(1L, 501L, request)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(perfect, below), null));
        when(candidateMapper.category(perfect)).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(perfect))
                .thenReturn(new RecommendationMaterializationResult(1L, true));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        recommendationService.generate(1L, 501L, request);

        verify(materializationService).materializeForRecommendation(perfect);
        verify(materializationService, never()).materializeForRecommendation(below);
    }

    private static RecommendationInputSnapshot input() {
        return new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                List.of(new TagInput(10L, "Fixture", TagType.DETAIL))
        );
    }

    private static RecommendationResultResponse currentResult() {
        List<RecommendationGroupResponse> groups = Arrays.stream(ProductCategory.values())
                .map(category -> new RecommendationGroupResponse(category, List.of()))
                .toList();
        return new RecommendationResultResponse(
                RecommendationStatus.CURRENT,
                "TAG_MATCH_RATIO_V1",
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
        return candidate(id, score, stable, "Fixture Product " + id);
    }

    private static ExternalProductCandidate candidate(
            int id,
            String score,
            boolean stable,
            String name
    ) {
        ProviderProductRef providerRef = stable
                ? ProviderProductRef.stable("fixture", Integer.toString(id), null, "store")
                : ProviderProductRef.unstable("fixture");
        return new ExternalProductCandidate(
                providerRef,
                name,
                null,
                "tops/shirts",
                null,
                URI.create("https://example.com/products/" + id + ".jpg"),
                score == null ? null : new BigDecimal(score),
                Instant.parse("2026-07-25T00:00:00Z")
        );
    }
}
