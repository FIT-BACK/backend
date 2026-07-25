package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.analysis.service.RecommendationResultProvider;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.ProductResponseMapper;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationItemResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RecommendationQueryService implements RecommendationResultProvider {

    private final AnalysisReportRepository analysisReportRepository;
    private final RecommendedItemRepository recommendedItemRepository;
    private final ProductResponseMapper productResponseMapper;

    public RecommendationQueryService(
            AnalysisReportRepository analysisReportRepository,
            RecommendedItemRepository recommendedItemRepository,
            ProductResponseMapper productResponseMapper
    ) {
        this.analysisReportRepository = analysisReportRepository;
        this.recommendedItemRepository = recommendedItemRepository;
        this.productResponseMapper = productResponseMapper;
    }

    @Override
    public RecommendationResultResponse findFor(AnalysisReport report) {
        List<RecommendedItem> items = report.getRecommendationGeneratedAt() == null
                ? List.of()
                : recommendedItemRepository
                        .findByReportIdOrderByCategoryAscRankNoAsc(report.getId());
        return result(report, items);
    }

    public RecommendationResultResponse findByReportId(Long reportId) {
        AnalysisReport report = analysisReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND));
        return findFor(report);
    }

    private RecommendationResultResponse result(
            AnalysisReport report,
            List<RecommendedItem> items
    ) {
        RecommendationStatus status = status(report);
        List<RecommendationGroupResponse> groups = java.util.Arrays.stream(ProductCategory.values())
                .map(category -> new RecommendationGroupResponse(
                        category,
                        items.stream()
                                .filter(item -> item.getCategory() == category)
                                .sorted(Comparator.comparing(RecommendedItem::getRankNo))
                                .map(this::toResponse)
                                .toList()
                ))
                .toList();
        return new RecommendationResultResponse(
                status,
                report.getResultScoreVersion(),
                groups,
                false,
                List.of()
        );
    }

    private RecommendationItemResponse toResponse(RecommendedItem item) {
        Product product = item.getProduct();
        return new RecommendationItemResponse(
                product.getId(),
                item.getRankNo(),
                product.getImageUrl(),
                product.getName(),
                product.getSellerName(),
                productResponseMapper.price(product),
                product.getPurchaseUrl(),
                item.getSimilarityScore(),
                item.getFinalScore(),
                item.getReasonCodeList(),
                product.getAvailability(),
                false
        );
    }

    private static RecommendationStatus status(AnalysisReport report) {
        if (report.getRecommendationGeneratedAt() == null) {
            return RecommendationStatus.NOT_GENERATED;
        }
        return report.hasRecommendationInputRevision(report.getResultInputRevision())
                ? RecommendationStatus.CURRENT
                : RecommendationStatus.STALE;
    }
}
