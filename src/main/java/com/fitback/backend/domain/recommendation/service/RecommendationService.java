package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.ProductCandidateMapper;
import com.fitback.backend.domain.product.service.ProductMaterializationService;
import com.fitback.backend.domain.product.service.ProductMaterializationService.RecommendationMaterializationResult;
import com.fitback.backend.domain.product.service.ProductProviderErrorMapper;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.domain.recommendation.dto.BrowserRerankingHandoff;
import com.fitback.backend.domain.recommendation.dto.RecommendationCreateResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationGenerateRequest;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.service.RecommendationScorer.Score;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.recommendation.service.model.RecommendationSelection;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.observability.RecommendationPerformanceTrace;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {

    static final String SCORE_VERSION = "IMAGE_TAG_WEIGHTED_V1";
    static final String THRESHOLD_SCORE_VERSION = "IMAGE_TAG_WEIGHTED_THR_V1";

    private static final int SEARCH_PAGE_SIZE = 20;
    private static final int MAX_ITEMS_PER_CATEGORY = 10;
    private static final BigDecimal TEMPORARY_IMAGE_SIMILARITY_SCORE =
            new BigDecimal("70");
    private static final String PROVIDER_PARTIAL_FAILURE = "PROVIDER_PARTIAL_FAILURE";
    private static final String MATERIALIZATION_SKIPPED = "MATERIALIZATION_SKIPPED";

    private final RecommendationInputReader inputReader;
    private final RecommendationInputCommandService inputCommandService;
    private final ProductCatalogPort productCatalogPort;
    private final ProductCandidateMapper candidateMapper;
    private final ProductMaterializationService materializationService;
    private final ImageComparisonCandidateSelector imageComparisonCandidateSelector;
    private final BrowserRerankingHandoffService browserRerankingHandoffService;
    private final RecommendationScorer scorer;
    private final RecommendationSetWriter setWriter;
    private final RecommendationQueryService queryService;

    public RecommendationService(
            RecommendationInputReader inputReader,
            RecommendationInputCommandService inputCommandService,
            ProductCatalogPort productCatalogPort,
            ProductCandidateMapper candidateMapper,
            ProductMaterializationService materializationService,
            ImageComparisonCandidateSelector imageComparisonCandidateSelector,
            BrowserRerankingHandoffService browserRerankingHandoffService,
            RecommendationScorer scorer,
            RecommendationSetWriter setWriter,
            RecommendationQueryService queryService
    ) {
        this.inputReader = inputReader;
        this.inputCommandService = inputCommandService;
        this.productCatalogPort = productCatalogPort;
        this.candidateMapper = candidateMapper;
        this.materializationService = materializationService;
        this.imageComparisonCandidateSelector = imageComparisonCandidateSelector;
        this.browserRerankingHandoffService = browserRerankingHandoffService;
        this.scorer = scorer;
        this.setWriter = setWriter;
        this.queryService = queryService;
    }

    public RecommendationCreateResponse generate(Long memberId, Long reportId) {
        return generate(memberId, reportId, null);
    }

    public RecommendationCreateResponse generate(
            Long memberId,
            Long reportId,
            RecommendationGenerateRequest request
    ) {
        boolean applyThreshold = request != null;
        RecommendationInputSnapshot input = applyThreshold
                ? inputCommandService.confirmAndRead(memberId, reportId, request)
                : inputReader.read(memberId, reportId);
        CandidateCollection candidateCollection = collectCandidates(
                input.category(),
                input.tags(),
                input.customTagNames()
        );
        BrowserRerankingHandoff browserReranking = browserRerankingHandoffService.create(
                memberId,
                input.category(),
                input.tags(),
                candidateCollection.candidates()
        );
        RecommendationPerformanceTrace.recordBrowserRerankingCandidateCount(
                browserReranking.candidates().size()
        );
        Set<String> warnings = new TreeSet<>(candidateCollection.warnings());
        List<ScoredCandidate> eligibleCandidates = RecommendationPerformanceTrace.measureStage(
                "scoring",
                () -> scoreEligibleCandidates(
                        input,
                        candidateCollection.candidates(),
                        applyThreshold
                )
        );
        List<MaterializedCandidate> materialized = RecommendationPerformanceTrace.measureStage(
                "materialization",
                () -> materializeCandidates(
                        eligibleCandidates,
                        warnings
                )
        );
        if (!eligibleCandidates.isEmpty() && materialized.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_PERSISTENCE_UNSUPPORTED);
        }

        List<RecommendationSelection> selections = selectTopItemsPerCategory(materialized);
        String scoreVersion = applyThreshold ? THRESHOLD_SCORE_VERSION : SCORE_VERSION;
        RecommendationPerformanceTrace.measureStage(
                "persistence",
                () -> setWriter.replaceCurrentSet(input, scoreVersion, selections)
        );
        RecommendationResultResponse result = RecommendationPerformanceTrace.measureStage(
                "responseHydrate",
                () -> queryService.findByReportId(memberId, reportId)
        );
        return new RecommendationCreateResponse(
                reportId,
                input.tagNames(),
                input.matchPercentage(),
                scoreVersion,
                result.recommendationStatus(),
                result.recommendationGroups(),
                browserReranking,
                !warnings.isEmpty(),
                List.copyOf(warnings)
        );
    }

    private CandidateCollection collectCandidates(
            ProductCategory category,
            List<TagInput> tags,
            List<String> customTagNames
    ) {
        // STYLE 태그를 상품 검색과 점수 계산 대상에서 제외하는 기존 추천 정책
        List<String> searchTagNames = Stream.concat(
                tags.stream()
                        .filter(tag -> tag.tagType() != TagType.STYLE)
                        .map(TagInput::name),
                customTagNames.stream()
        ).toList();
        List<String> searchTagKinds = Stream.concat(
                tags.stream()
                        .filter(tag -> tag.tagType() != TagType.STYLE)
                        .map(tag -> tag.tagType().name()),
                IntStream.range(0, customTagNames.size())
                        .mapToObj(index -> "CUSTOM_" + (index + 1))
        ).toList();

        // 일부 검색 실패 시 성공한 검색 결과로 추천을 계속하기 위한 배치·실패 분리 수집
        List<List<ExternalProductCandidate>> candidateBatches = new ArrayList<>();
        List<BusinessException> failures = new ArrayList<>();
        int successfulSearches = 0;
        int searchedCandidateCount = 0;
        int categoryFilteredCandidateCount = 0;

        // 태그 입력 순서를 이후 라운드 로빈의 검색 배치 순서로 유지
        for (int index = 0; index < searchTagNames.size(); index++) {
            String tagName = searchTagNames.get(index);
            String tagKind = searchTagKinds.get(index);
            try {
                ProductSearchResult searchResult = RecommendationPerformanceTrace.measureSearchCatalog(
                        new RecommendationPerformanceTrace.SearchCatalogCallInput(
                                index + 1,
                                tagName,
                                tagKind,
                                category.name()
                        ),
                        () -> productCatalogPort.search(
                                new ProductSearchQuery(tagName, category, null, SEARCH_PAGE_SIZE)
                        ),
                        result -> result.items().size()
                );
                successfulSearches++;
                searchedCandidateCount += searchResult.items().size();

                // 검색어 내부의 공급자 상품 순위 보존을 위한 결과 목록 단위 저장
                List<ExternalProductCandidate> categoryFiltered =
                        RecommendationPerformanceTrace.measureStage(
                                "categoryFiltering",
                                () -> searchResult.items().stream()
                                        .filter(candidate -> candidateMapper.category(candidate) == category)
                                        .toList()
                        );
                RecommendationPerformanceTrace.recordCategoryFilteredResultCount(
                        index + 1,
                        categoryFiltered.size()
                );
                categoryFilteredCandidateCount += categoryFiltered.size();
                candidateBatches.add(categoryFiltered);
            } catch (ProductProviderException exception) {
                // 전체 실패와 부분 실패를 구분하기 위한 공급자 오류 누적
                failures.add(ProductProviderErrorMapper.toBusinessException(exception));
            }
        }

        // 모든 공급자 검색 실패 시 기존 추천 세트 보존을 위한 즉시 실패
        if (successfulSearches == 0 && !failures.isEmpty()) {
            throw failures.getFirst();
        }

        // 점수 계산과 이미지 비교 전에 처리 예산을 제한하기 위한 후보 선별
        ImageComparisonCandidateSelector.SelectionResult selection =
                RecommendationPerformanceTrace.measureStage(
                        "candidateMergeDedup",
                        () -> imageComparisonCandidateSelector.select(candidateBatches)
                );
        RecommendationPerformanceTrace.recordCandidateCounts(
                searchedCandidateCount,
                categoryFilteredCandidateCount,
                selection.candidates().size()
        );
        ImageComparisonCandidateSelector.SelectionMetrics selectionMetrics = selection.metrics();
        RecommendationPerformanceTrace.recordSelectorCounts(
                selectionMetrics.inputCandidateCount(),
                selectionMetrics.orderedCandidateCount(),
                selectionMetrics.outputCandidateCount(),
                selectionMetrics.invalidProviderReferenceDropCount(),
                selectionMetrics.missingImageUrlDropCount(),
                selectionMetrics.duplicateDropCount(),
                selectionMetrics.limitOverflowDropCount(),
                selectionMetrics.otherDropCount()
        );
        List<String> warnings = new ArrayList<>();

        // 일부 검색 실패를 정상 결과와 함께 전달하기 위한 부분 성공 경고
        if (!failures.isEmpty()) {
            warnings.add(PROVIDER_PARTIAL_FAILURE);
        }

        // 선별 단계로 이동한 불안정 식별자 제외의 기존 경고 계약 유지
        if (selection.unsupportedReferenceSkipped()) {
            warnings.add(MATERIALIZATION_SKIPPED);
        }
        return new CandidateCollection(selection.candidates(), List.copyOf(warnings));
    }

    private List<ScoredCandidate> scoreEligibleCandidates(
            RecommendationInputSnapshot input,
            List<ExternalProductCandidate> candidates,
            boolean applyThreshold
    ) {
        BigDecimal threshold = BigDecimal.valueOf(input.matchPercentage());
        return candidates.stream()
                .map(candidate -> new ScoredCandidate(
                        candidate,
                        scorer.score(
                                input.tags(),
                                TEMPORARY_IMAGE_SIMILARITY_SCORE,
                                candidate
                        )
                ))
                .filter(candidate -> !applyThreshold
                        || candidate.score().similarityScore().compareTo(threshold) >= 0)
                .toList();
    }

    private List<MaterializedCandidate> materializeCandidates(
            List<ScoredCandidate> candidates,
            Set<String> warnings
    ) {
        List<MaterializedCandidate> materialized = new ArrayList<>();
        for (ScoredCandidate scoredCandidate : candidates) {
            ExternalProductCandidate candidate = scoredCandidate.candidate();
            Score score = scoredCandidate.score();
            try {
                RecommendationMaterializationResult result =
                        materializationService.materializeForRecommendation(candidate);
                materialized.add(new MaterializedCandidate(
                        result.productId(),
                        candidate.providerRef(),
                        candidateMapper.category(candidate),
                        score.similarityScore(),
                        score.reasonCodes()
                ));
            } catch (BusinessException exception) {
                if (canSkipMaterialization(exception.getErrorCode())) {
                    warnings.add(MATERIALIZATION_SKIPPED);
                    continue;
                }
                throw exception;
            }
        }
        return materialized;
    }

    private static List<RecommendationSelection> selectTopItemsPerCategory(
            List<MaterializedCandidate> candidates
    ) {
        Comparator<MaterializedCandidate> order = Comparator
                .comparing(MaterializedCandidate::score, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.providerRef().provider())
                .thenComparing(candidate -> candidate.providerRef().externalProductId())
                .thenComparing(RecommendationService::providerIdentity)
                .thenComparing(MaterializedCandidate::productId);
        List<RecommendationSelection> selections = new ArrayList<>();
        for (ProductCategory category : ProductCategory.values()) {
            List<MaterializedCandidate> categoryItems = candidates.stream()
                    .filter(candidate -> candidate.category() == category)
                    .sorted(order)
                    .limit(MAX_ITEMS_PER_CATEGORY)
                    .toList();
            for (int index = 0; index < categoryItems.size(); index++) {
                MaterializedCandidate candidate = categoryItems.get(index);
                selections.add(new RecommendationSelection(
                        candidate.productId(),
                        index + 1,
                        category,
                        candidate.score(),
                        candidate.score(),
                        candidate.reasonCodes()
                ));
            }
        }
        return List.copyOf(selections);
    }

    private static boolean canSkipMaterialization(ErrorCode errorCode) {
        return errorCode == ErrorCode.PRODUCT_REFERENCE_UNSUPPORTED
                || errorCode == ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID;
    }

    private static String providerIdentity(MaterializedCandidate candidate) {
        return providerIdentity(candidate.providerRef());
    }

    private static String providerIdentity(ProviderProductRef providerRef) {
        return String.join(
                "\u0000",
                providerRef.provider(),
                nullable(providerRef.externalProductId()),
                nullable(providerRef.externalVariantId()),
                nullable(providerRef.merchantId())
        );
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private record CandidateCollection(
            List<ExternalProductCandidate> candidates,
            List<String> warnings
    ) {
    }

    private record ScoredCandidate(
            ExternalProductCandidate candidate,
            Score score
    ) {
    }

    private record MaterializedCandidate(
            Long productId,
            ProviderProductRef providerRef,
            ProductCategory category,
            BigDecimal score,
            List<String> reasonCodes
    ) {
    }
}
