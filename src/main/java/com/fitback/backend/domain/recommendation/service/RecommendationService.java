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
import com.fitback.backend.domain.recommendation.dto.RecommendationCreateResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.service.RecommendationScorer.Score;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationSelection;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {

    static final String SCORE_VERSION = "SIMILARITY_V1";

    private static final int SEARCH_PAGE_SIZE = 20;
    private static final int MAX_ITEMS_PER_CATEGORY = 5;
    private static final String PROVIDER_PARTIAL_FAILURE = "PROVIDER_PARTIAL_FAILURE";
    private static final String MATERIALIZATION_SKIPPED = "MATERIALIZATION_SKIPPED";

    private final RecommendationInputReader inputReader;
    private final ProductCatalogPort productCatalogPort;
    private final ProductCandidateMapper candidateMapper;
    private final ProductMaterializationService materializationService;
    private final RecommendationScorer scorer;
    private final RecommendationSetWriter setWriter;
    private final RecommendationQueryService queryService;

    public RecommendationService(
            RecommendationInputReader inputReader,
            ProductCatalogPort productCatalogPort,
            ProductCandidateMapper candidateMapper,
            ProductMaterializationService materializationService,
            RecommendationScorer scorer,
            RecommendationSetWriter setWriter,
            RecommendationQueryService queryService
    ) {
        this.inputReader = inputReader;
        this.productCatalogPort = productCatalogPort;
        this.candidateMapper = candidateMapper;
        this.materializationService = materializationService;
        this.scorer = scorer;
        this.setWriter = setWriter;
        this.queryService = queryService;
    }

    public RecommendationCreateResponse generate(Long memberId, Long reportId) {
        RecommendationInputSnapshot input = inputReader.read(memberId, reportId);
        CandidateCollection candidateCollection = collectCandidates(input.tagNames());
        Set<String> warnings = new TreeSet<>(candidateCollection.warnings());
        List<MaterializedCandidate> materialized = materializeCandidates(
                input.tagNames(),
                candidateCollection.candidates(),
                warnings
        );
        if (!candidateCollection.candidates().isEmpty() && materialized.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_PERSISTENCE_UNSUPPORTED);
        }

        List<RecommendationSelection> selections = selectTopFivePerCategory(materialized);
        setWriter.replaceCurrentSet(input, SCORE_VERSION, selections);
        RecommendationResultResponse result = queryService.findByReportId(memberId, reportId);
        return new RecommendationCreateResponse(
                reportId,
                input.tagNames(),
                SCORE_VERSION,
                result.recommendationStatus(),
                result.recommendationGroups(),
                !warnings.isEmpty(),
                List.copyOf(warnings)
        );
    }

    private CandidateCollection collectCandidates(List<String> tagNames) {
        Map<String, ExternalProductCandidate> candidatesByKey = new LinkedHashMap<>();
        List<BusinessException> failures = new ArrayList<>();
        int successfulSearches = 0;
        for (String tagName : tagNames) {
            try {
                ProductSearchResult searchResult = productCatalogPort.search(
                        new ProductSearchQuery(tagName, null, null, SEARCH_PAGE_SIZE)
                );
                successfulSearches++;
                searchResult.items().forEach(candidate ->
                        candidatesByKey.putIfAbsent(candidateKey(candidate), candidate));
            } catch (ProductProviderException exception) {
                failures.add(ProductProviderErrorMapper.toBusinessException(exception));
            }
        }
        if (successfulSearches == 0 && !failures.isEmpty()) {
            throw failures.getFirst();
        }
        List<String> warnings = failures.isEmpty()
                ? List.of()
                : List.of(PROVIDER_PARTIAL_FAILURE);
        return new CandidateCollection(List.copyOf(candidatesByKey.values()), warnings);
    }

    private List<MaterializedCandidate> materializeCandidates(
            List<String> tagNames,
            List<ExternalProductCandidate> candidates,
            Set<String> warnings
    ) {
        List<MaterializedCandidate> materialized = new ArrayList<>();
        for (ExternalProductCandidate candidate : candidates) {
            Score score = scorer.score(tagNames, candidate);
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

    private static List<RecommendationSelection> selectTopFivePerCategory(
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

    private static String candidateKey(ExternalProductCandidate candidate) {
        ProviderProductRef providerRef = candidate.providerRef();
        if (providerRef.stable()) {
            return "stable:" + providerIdentity(providerRef);
        }
        return String.join(
                "\u0000",
                "snapshot",
                providerRef.provider(),
                candidate.name(),
                nullable(candidate.categoryPath()),
                candidate.observedAt().toString()
        );
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

    private record MaterializedCandidate(
            Long productId,
            ProviderProductRef providerRef,
            ProductCategory category,
            BigDecimal score,
            List<String> reasonCodes
    ) {
    }
}
