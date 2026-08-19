package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import com.fitback.backend.global.observability.RecommendationPerformanceTrace;
import com.fitback.backend.global.util.HmacUtil;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    private static final int TEST_CANDIDATE_LIMIT = 30;

    @Mock
    private RecommendationInputReader inputReader;

    @Mock
    private RecommendationInputCommandService inputCommandService;

    @Mock
    private HmacUtil hmacUtil;

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

    @Mock
    private BrowserRerankingHandoffService browserRerankingHandoffService;

    @Spy
    private RecommendationScorer scorer =
            new RecommendationScorer(new RecommendationRetrievalQueryPlanner());

    private RecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        lenient().when(hmacUtil.hashHex(any())).thenReturn("a".repeat(64));
        lenient().when(candidateMapper.category(any())).thenReturn(ProductCategory.TOP);
        lenient().when(browserRerankingHandoffService.create(
                org.mockito.ArgumentMatchers.anyLong(),
                any(),
                any(),
                any()
        )).thenReturn(new com.fitback.backend.domain.recommendation.dto.BrowserRerankingHandoff(
                ProductCategory.TOP,
                List.of()
        ));
        recommendationService = recommendationService(TEST_CANDIDATE_LIMIT);
    }

    private RecommendationService recommendationService(int candidateLimit) {
        return new RecommendationService(
                inputReader,
                inputCommandService,
                hmacUtil,
                productCatalogPort,
                new RecommendationRetrievalQueryPlanner(),
                candidateMapper,
                materializationService,
                new ImageComparisonCandidateSelector(
                        new MultiTagPriorityImageComparisonCandidateOrderingPolicy(),
                        candidateLimit
                ),
                browserRerankingHandoffService,
                scorer,
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
                org.mockito.ArgumentMatchers.eq("IMAGE_TAG_WEIGHTED_V1"),
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
    void handsOffAtMostThirtyCandidatesAfterSelector() {
        RecommendationInputSnapshot input = input();
        List<ExternalProductCandidate> candidates = IntStream.rangeClosed(1, 35)
                .mapToObj(id -> candidate(id, null, true))
                .toList();
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(candidates, null));
        when(candidateMapper.category(any())).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(any()))
                .thenReturn(new RecommendationMaterializationResult(1L, true));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            recommendationService.generate(1L, 501L);
            trace = scope.snapshot();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExternalProductCandidate>> candidatesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(browserRerankingHandoffService).create(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(ProductCategory.TOP),
                org.mockito.ArgumentMatchers.eq(input.tags()),
                candidatesCaptor.capture()
        );
        assertThat(candidatesCaptor.getValue()).hasSize(30);
        assertThat(trace.searchCatalogCalls()).hasSize(1);
        assertThat(trace.searchCatalogTiming().invocationCount()).isEqualTo(1);
        assertThat(trace.stages()).containsKeys(
                "categoryFiltering",
                "candidateMergeDedup",
                "scoring",
                "persistence",
                "responseHydrate"
        );
        assertThat(trace.candidateCounts()).isEqualTo(
                new RecommendationPerformanceTrace.CandidateCounts(35, 35, 30)
        );
        assertThat(trace.searchCatalogCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.queryIndex()).isEqualTo(1);
                    assertThat(call.rawResultCount()).isEqualTo(35);
                    assertThat(call.categoryFilteredResultCount()).isEqualTo(35);
                    assertThat(call.providerSucceeded()).isTrue();
                    assertThat(call.queryFingerprint()).isEqualTo("hmac-sha256:" + "a".repeat(64));
                });
        assertThat(trace.selectorCounts()).isEqualTo(
                new RecommendationPerformanceTrace.SelectorCounts(35, 35, 30, 0, 0, 0, 5, 0)
        );
        assertThat(trace.browserRerankingCandidateCount()).isZero();
    }

    @Test
    void ranksCandidatesByWeightedSimilarityScore() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                ProductCategory.TOP,
                List.of(
                        new TagInput(10L, "Fixture", TagType.DETAIL),
                        new TagInput(20L, "Perfect", TagType.COLOR)
                )
        );
        ExternalProductCandidate partial = candidate(
                1,
                null,
                true,
                "Fixture Product"
        );
        ExternalProductCandidate full = candidate(
                2,
                null,
                true,
                "Fixture Perfect Product"
        );
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(partial, full), null));
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

        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            recommendationService.generate(1L, 501L);
            trace = scope.snapshot();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("IMAGE_TAG_WEIGHTED_V1"),
                selectionsCaptor.capture()
        );
        assertThat(selectionsCaptor.getValue())
                .extracting(
                        RecommendationSelection::productId,
                        RecommendationSelection::rankNo,
                        RecommendationSelection::similarityScore
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                2L,
                                1,
                                new BigDecimal("79.00")
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                1L,
                                2,
                                // 매칭된 태그가 COLOR("Perfect")가 아니라 DETAIL 하나뿐이라
                                // COLOR 가중치(6) 도입 이후엔 1/7 비율이 적용돼 64.00이 아닌
                                // 53.29가 된다.
                                new BigDecimal("53.29")
                        )
                );
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
    void excludesCandidateWithoutImageBeforeScoringAndMaterialization() {
        RecommendationInputSnapshot input = input();
        ExternalProductCandidate missingImage = candidate(1, "0.90", true, false);
        ExternalProductCandidate candidateWithImage = candidate(2, "0.80", true, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(
                        List.of(missingImage, candidateWithImage),
                        null
                ));
        when(candidateMapper.category(candidateWithImage)).thenReturn(ProductCategory.TOP);
        when(materializationService.materializeForRecommendation(candidateWithImage))
                .thenReturn(new RecommendationMaterializationResult(2L, true));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        recommendationService.generate(1L, 501L);

        verify(scorer, never()).score(
                input.tags(),
                new BigDecimal("70"),
                missingImage
        );
        verify(materializationService, never()).materializeForRecommendation(missingImage);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("IMAGE_TAG_WEIGHTED_V1"),
                selectionsCaptor.capture()
        );
        assertThat(selectionsCaptor.getValue())
                .extracting(RecommendationSelection::productId)
                .containsExactly(2L);
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

        verify(setWriter).replaceCurrentSet(input, "IMAGE_TAG_WEIGHTED_V1", List.of());
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
        verify(hmacUtil, never()).hashHex(any());
    }

    @Test
    void tracesRawSearchZeroBeforeCategoryFilteringAndSelection() {
        RecommendationInputSnapshot input = input();
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(), null));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            recommendationService.generate(1L, 501L);
            trace = scope.snapshot();
        }

        assertThat(trace.searchCatalogCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.rawResultCount()).isZero();
                    assertThat(call.categoryFilteredResultCount()).isZero();
                    assertThat(call.providerSucceeded()).isTrue();
                });
        assertThat(trace.selectorCounts()).isEqualTo(
                new RecommendationPerformanceTrace.SelectorCounts(0, 0, 0, 0, 0, 0, 0, 0)
        );
        assertThat(trace.browserRerankingCandidateCount()).isZero();
    }

    @Test
    void tracesCategoryFilterZeroAfterNonEmptyProviderSearch() {
        RecommendationInputSnapshot input = input();
        ExternalProductCandidate candidate = candidate(1, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(candidate), null));
        when(candidateMapper.category(candidate)).thenReturn(ProductCategory.BOTTOM);
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            recommendationService.generate(1L, 501L);
            trace = scope.snapshot();
        }

        assertThat(trace.searchCatalogCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.rawResultCount()).isEqualTo(1);
                    assertThat(call.categoryFilteredResultCount()).isZero();
                    assertThat(call.providerSucceeded()).isTrue();
                });
        assertThat(trace.selectorCounts()).isEqualTo(
                new RecommendationPerformanceTrace.SelectorCounts(0, 0, 0, 0, 0, 0, 0, 0)
        );
    }

    @Test
    void omitsCustomTagFromRetrievalAndUsesCategoryFallback() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                ProductCategory.TOP,
                List.of(),
                List.of("Fixture")
        );
        ExternalProductCandidate candidate = candidate(1, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "",
                ProductCategory.TOP,
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
                ProductCategory.TOP,
                null,
                20
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("IMAGE_TAG_WEIGHTED_V1"),
                selectionsCaptor.capture()
        );
        verify(scorer).score(input.tags(), new BigDecimal("70"), candidate);
        assertThat(selectionsCaptor.getValue()).singleElement().satisfies(selection -> {
            assertThat(selection.similarityScore()).isEqualByComparingTo("79.00");
            assertThat(selection.finalScore()).isEqualByComparingTo("79.00");
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
                ProductCategory.TOP,
                List.of(
                        new TagInput(10L, "Fixture", TagType.STYLE),
                        new TagInput(20L, "unmatched", TagType.DETAIL)
                ),
                List.of()
        );
        ExternalProductCandidate candidate = candidate(1, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "",
                ProductCategory.TOP,
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
                ProductCategory.TOP,
                null,
                20
        ));
        verify(productCatalogPort, never()).search(new ProductSearchQuery(
                "unmatched",
                ProductCategory.TOP,
                null,
                20
        ));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecommendationSelection>> selectionsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(setWriter).replaceCurrentSet(
                org.mockito.ArgumentMatchers.eq(input),
                org.mockito.ArgumentMatchers.eq("IMAGE_TAG_WEIGHTED_V1"),
                selectionsCaptor.capture()
        );
        assertThat(selectionsCaptor.getValue()).singleElement().satisfies(selection -> {
            assertThat(selection.similarityScore()).isEqualByComparingTo("49.00");
            assertThat(selection.finalScore()).isEqualByComparingTo("49.00");
            assertThat(selection.reasonCodes()).containsExactly("NO_ATTRIBUTE_MATCH");
        });
    }

    @Test
    void searchesPlannedEnglishQueriesInDeterministicOrder() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                ProductCategory.TOP,
                List.of(
                        new TagInput(10L, "A라인", TagType.SILHOUETTE),
                        new TagInput(20L, "네이비", TagType.COLOR),
                        new TagInput(30L, "미니멀", TagType.STYLE),
                        new TagInput(40L, "브이넥", TagType.DETAIL),
                        new TagInput(50L, "코튼", TagType.MATERIAL)
                ),
                List.of("사용자 태그")
        );
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(any(ProductSearchQuery.class)))
                .thenReturn(new ProductSearchResult(List.of(), null));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            recommendationService.generate(1L, 501L);
            trace = scope.snapshot();
        }

        ArgumentCaptor<ProductSearchQuery> queryCaptor =
                ArgumentCaptor.forClass(ProductSearchQuery.class);
        verify(productCatalogPort, org.mockito.Mockito.times(5)).search(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues())
                .extracting(ProductSearchQuery::keyword)
                .containsExactly("a-line", "a-line navy", "v-neck", "v-neck navy", "");
        assertThat(queryCaptor.getAllValues())
                .extracting(ProductSearchQuery::category)
                .containsOnly(ProductCategory.TOP);
        assertThat(queryCaptor.getAllValues())
                .extracting(ProductSearchQuery::pageSize)
                .containsOnly(20);
        assertThat(trace.searchCatalogCalls())
                .extracting(RecommendationPerformanceTrace.CatalogCall::tagKind)
                .containsExactly(
                        "SILHOUETTE",
                        "SILHOUETTE_COLOR",
                        "DETAIL",
                        "DETAIL_COLOR",
                        "CATEGORY"
                );
        assertThat(trace.searchCatalogCalls())
                .allSatisfy(call -> {
                    assertThat(call.queryFingerprint())
                            .isEqualTo("hmac-sha256:" + "a".repeat(64));
                    assertThat(call.rawResultCount()).isZero();
                    assertThat(call.categoryFilteredResultCount()).isZero();
                });
    }

    @Test
    void excludesCandidatesOutsideAnalysisCategory() {
        RecommendationInputSnapshot input = input();
        ExternalProductCandidate top = candidate(1, null, true);
        ExternalProductCandidate outer = candidate(2, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "",
                ProductCategory.TOP,
                null,
                20
        ))).thenReturn(new ProductSearchResult(List.of(top, outer), null));
        when(candidateMapper.category(top)).thenReturn(ProductCategory.TOP);
        when(candidateMapper.category(outer)).thenReturn(ProductCategory.OUTER);
        when(materializationService.materializeForRecommendation(top))
                .thenReturn(new RecommendationMaterializationResult(1L, true));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        recommendationService.generate(1L, 501L);

        verify(materializationService).materializeForRecommendation(top);
        verify(materializationService, never()).materializeForRecommendation(outer);
        verify(scorer, never()).score(input.tags(), new BigDecimal("70"), outer);
    }

    @Test
    void selectsCandidatesAcrossSearchResultsBeforeScoring() {
        recommendationService = recommendationService(2);
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                ProductCategory.TOP,
                List.of(
                        new TagInput(10L, "A라인", TagType.SILHOUETTE),
                        new TagInput(20L, "브이넥", TagType.DETAIL)
                ),
                List.of()
        );
        ExternalProductCandidate firstRankFromFirstSearch = candidate(1, null, true);
        ExternalProductCandidate secondRankFromFirstSearch = candidate(2, null, true);
        ExternalProductCandidate firstRankFromSecondSearch = candidate(3, null, true);
        ExternalProductCandidate secondRankFromSecondSearch = candidate(4, null, true);
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "a-line",
                ProductCategory.TOP,
                null,
                20
        ))).thenReturn(new ProductSearchResult(
                List.of(firstRankFromFirstSearch, secondRankFromFirstSearch),
                null
        ));
        when(productCatalogPort.search(new ProductSearchQuery(
                "v-neck",
                ProductCategory.TOP,
                null,
                20
        ))).thenReturn(new ProductSearchResult(
                List.of(firstRankFromSecondSearch, secondRankFromSecondSearch),
                null
        ));
        when(productCatalogPort.search(new ProductSearchQuery(
                "",
                ProductCategory.TOP,
                null,
                20
        ))).thenReturn(new ProductSearchResult(List.of(), null));
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
    void usesCategoryFallbackWhenOnlyStyleTagsExist() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                70,
                ProductCategory.TOP,
                List.of(new TagInput(10L, "스타일", TagType.STYLE)),
                List.of()
        );
        when(inputReader.read(1L, 501L)).thenReturn(input);
        when(productCatalogPort.search(new ProductSearchQuery(
                "",
                ProductCategory.TOP,
                null,
                20
        ))).thenReturn(new ProductSearchResult(List.of(), null));
        when(queryService.findByReportId(1L, 501L)).thenReturn(currentResult());

        RecommendationCreateResponse response = recommendationService.generate(1L, 501L);

        verify(productCatalogPort).search(new ProductSearchQuery(
                "",
                ProductCategory.TOP,
                null,
                20
        ));
        verify(setWriter).replaceCurrentSet(input, "IMAGE_TAG_WEIGHTED_V1", List.of());
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
                ProductCategory.TOP,
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
                "IMAGE_TAG_WEIGHTED_THR_V1",
                List.of()
        );
        assertThat(response.recommendationStatus()).isEqualTo(RecommendationStatus.CURRENT);
        assertThat(response.scoreVersion()).isEqualTo("IMAGE_TAG_WEIGHTED_THR_V1");
    }

    @Test
    void thresholdUsesWeightedScoreAndIncludesOnlyCandidateAtSeventyNine() {
        RecommendationGenerateRequest request = new RecommendationGenerateRequest(
                List.of(10L, 20L),
                List.of(),
                79
        );
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                2,
                79,
                ProductCategory.TOP,
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
                ProductCategory.TOP,
                List.of(new TagInput(10L, "Fixture", TagType.DETAIL))
        );
    }

    private static RecommendationResultResponse currentResult() {
        List<RecommendationGroupResponse> groups = Arrays.stream(ProductCategory.values())
                .map(category -> new RecommendationGroupResponse(category, List.of()))
                .toList();
        return new RecommendationResultResponse(
                RecommendationStatus.CURRENT,
                "IMAGE_TAG_WEIGHTED_V1",
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
        return candidate(id, score, stable, "Fixture Product " + id, true);
    }

    private static ExternalProductCandidate candidate(
            int id,
            String score,
            boolean stable,
            boolean hasImage
    ) {
        return candidate(id, score, stable, "Fixture Product " + id, hasImage);
    }

    private static ExternalProductCandidate candidate(
            int id,
            String score,
            boolean stable,
            String name
    ) {
        return candidate(id, score, stable, name, true);
    }

    private static ExternalProductCandidate candidate(
            int id,
            String score,
            boolean stable,
            String name,
            boolean hasImage
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
                hasImage ? URI.create("https://example.com/products/" + id + ".jpg") : null,
                score == null ? null : new BigDecimal(score),
                Instant.parse("2026-07-25T00:00:00Z")
        );
    }
}
