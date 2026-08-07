package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.repository.MemberNotificationSettingRepository;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
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

    private static final String ANALYSIS_COMPLETE_TITLE = "AI 분석이 완료됐어요";
    private static final String ANALYSIS_COMPLETE_BODY = "요청하신 스타일 분석과 추천 결과 확인이 가능합니다.";

    private final AnalysisReportRepository analysisReportRepository;
    private final ProductRepository productRepository;
    private final RecommendedItemRepository recommendedItemRepository;
    private final RecommendationInputSnapshotFactory snapshotFactory;
    private final NotificationRepository notificationRepository;
    private final MemberNotificationSettingRepository notificationSettingRepository;
    private final Clock clock;

    public RecommendationSetWriter(
            AnalysisReportRepository analysisReportRepository,
            ProductRepository productRepository,
            RecommendedItemRepository recommendedItemRepository,
            RecommendationInputSnapshotFactory snapshotFactory,
            NotificationRepository notificationRepository,
            MemberNotificationSettingRepository notificationSettingRepository,
            Clock clock
    ) {
        this.analysisReportRepository = analysisReportRepository;
        this.productRepository = productRepository;
        this.recommendedItemRepository = recommendedItemRepository;
        this.snapshotFactory = snapshotFactory;
        this.notificationRepository = notificationRepository;
        this.notificationSettingRepository = notificationSettingRepository;
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

        notifyAnalysisComplete(report);
    }

    // 리포트 요청자에게 분석 완료 알림 생성 (알림을 꺼둔 경우 제외)
    private void notifyAnalysisComplete(AnalysisReport report) {
        Member requester = report.getMember();
        if (!isAnalysisCompleteNotificationEnabled(requester)) {
            return;
        }

        Notification notification = Notification.create(
                requester,
                NotificationType.ANALYSIS_COMPLETE,
                null,
                null,
                report.getId(),
                null,
                ANALYSIS_COMPLETE_TITLE,
                ANALYSIS_COMPLETE_BODY
        );
        notificationRepository.save(notification);
    }

    // 설정 row가 없으면 기본값(허용)으로 간주
    private boolean isAnalysisCompleteNotificationEnabled(Member requester) {
        return notificationSettingRepository.findById(requester.getId())
                .map(MemberNotificationSetting::getAnalysisCompleteEnabled)
                .orElse(true);
    }

}
