package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationSelection;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationSetWriter {

    private final AnalysisReportRepository analysisReportRepository;
    private final ProductRepository productRepository;
    private final RecommendedItemRepository recommendedItemRepository;
    private final RecommendationInputSnapshotFactory snapshotFactory;
    private final Clock clock;

    public RecommendationSetWriter(
            AnalysisReportRepository analysisReportRepository,
            ProductRepository productRepository,
            RecommendedItemRepository recommendedItemRepository,
            RecommendationInputSnapshotFactory snapshotFactory,
            Clock clock
    ) {
        this.analysisReportRepository = analysisReportRepository;
        this.productRepository = productRepository;
        this.recommendedItemRepository = recommendedItemRepository;
        this.snapshotFactory = snapshotFactory;
        this.clock = clock;
    }

    @Transactional
    public void replaceCurrentSet(
            RecommendationInputSnapshot input,
            String scoreVersion,
            List<RecommendationSelection> selections
    ) {
        AnalysisReport report = analysisReportRepository
                .findOwnedReportForRecommendationUpdate(input.reportId(), input.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND));
        if (!snapshotFactory.matches(report, input)) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_INPUT_CHANGED);
        }

        Map<Long, Product> productsById = productRepository
                .findAllById(selections.stream().map(RecommendationSelection::productId).toList())
                .stream()
                .collect(Collectors.toMap(
                        Product::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (productsById.size() != selections.size()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        recommendedItemRepository.deleteCurrentSetByReportId(report.getId());
        recommendedItemRepository.flush();
        List<RecommendedItem> items = selections.stream()
                .map(selection -> RecommendedItem.create(
                        report,
                        productsById.get(selection.productId()),
                        input.inputRevision(),
                        selection.rankNo(),
                        selection.category(),
                        selection.similarityScore(),
                        selection.finalScore(),
                        scoreVersion,
                        selection.reasonCodes()
                ))
                .toList();
        recommendedItemRepository.saveAll(items);
        report.markRecommendationGenerated(input.inputRevision(), scoreVersion, clock.instant());
    }

}
