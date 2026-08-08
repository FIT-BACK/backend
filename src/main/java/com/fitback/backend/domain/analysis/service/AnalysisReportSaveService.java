package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.analysis.dto.AnalysisListResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisReportSaveRequest;
import com.fitback.backend.domain.analysis.dto.AnalysisReportSaveResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisSummaryResponse;
import com.fitback.backend.domain.analysis.dto.SavedAnalysisItemResponse;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.entity.ReportCustomTag;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.entity.SavedAnalysisItem;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.closet.repository.SavedAnalysisItemRepository;
import com.fitback.backend.domain.image.service.ImageUploadService;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisReportSaveService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final AnalysisReportRepository analysisReportRepository;
    private final ClosetSaveRepository closetSaveRepository;
    private final SavedAnalysisItemRepository savedAnalysisItemRepository;
    private final RecommendedItemRepository recommendedItemRepository;
    private final ImageUploadService imageUploadService;
    private final EntityManager entityManager;

    @Transactional
    public SaveOutcome save(
            Long memberId,
            Long reportId,
            AnalysisReportSaveRequest request
    ) {
        AnalysisReport report = analysisReportRepository
                .findOwnedReportForSave(reportId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ANALYSIS_REPORT_NOT_FOUND
                ));
        return closetSaveRepository
                .findByMemberIdAndTargetTypeAndTargetId(
                        memberId,
                        ClosetTargetType.ANALYSIS_REPORT,
                        reportId
                )
                .map(save -> new SaveOutcome(toResponse(reportId, save), false))
                .orElseGet(() -> createSave(report, request));
    }

    @Transactional
    public AnalysisReportSaveResponse unsave(Long memberId, Long reportId) {
        analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(reportId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ANALYSIS_REPORT_NOT_FOUND
                ));
        closetSaveRepository
                .findByMemberIdAndTargetTypeAndTargetId(
                        memberId,
                        ClosetTargetType.ANALYSIS_REPORT,
                        reportId
                )
                .ifPresent(save -> {
                    savedAnalysisItemRepository.deleteAllByClosetSaveId(save.getId());
                    closetSaveRepository.delete(save);
                });
        return AnalysisReportSaveResponse.unsaved(reportId);
    }

    @Transactional(readOnly = true)
    public AnalysisListResponse getSavedReports(
            Long memberId,
            Long cursor,
            Integer requestedPageSize
    ) {
        int pageSize = validatePageSize(requestedPageSize);
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        PageRequest pageRequest = PageRequest.of(0, pageSize);
        Slice<ClosetSave> saves = cursor == null
                ? closetSaveRepository.findByMemberIdAndTargetTypeOrderByIdDesc(
                        memberId,
                        ClosetTargetType.ANALYSIS_REPORT,
                        pageRequest
                )
                : closetSaveRepository.findByMemberIdAndTargetTypeAndIdLessThanOrderByIdDesc(
                        memberId,
                        ClosetTargetType.ANALYSIS_REPORT,
                        cursor,
                        pageRequest
                );
        List<Long> reportIds = saves.getContent().stream()
                .map(ClosetSave::getTargetId)
                .toList();
        Map<Long, AnalysisReport> reportsById = reportIds.isEmpty()
                ? Map.of()
                : analysisReportRepository
                        .findByIdInAndMemberIdAndDeletedAtIsNull(reportIds, memberId)
                        .stream()
                        .collect(Collectors.toMap(
                                AnalysisReport::getId,
                                Function.identity()
                        ));
        List<AnalysisSummaryResponse> items = saves.getContent().stream()
                .map(save -> {
                    AnalysisReport report = reportsById.get(save.getTargetId());
                    if (report == null) {
                        throw new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND);
                    }
                    return toSummary(report, save);
                })
                .toList();
        Long nextCursor = saves.hasNext() && !saves.getContent().isEmpty()
                ? saves.getContent().getLast().getId()
                : null;
        return new AnalysisListResponse(items, nextCursor, saves.hasNext(), pageSize);
    }

    @Transactional(readOnly = true)
    public SavedState getState(Long memberId, Long reportId) {
        return closetSaveRepository
                .findByMemberIdAndTargetTypeAndTargetId(
                        memberId,
                        ClosetTargetType.ANALYSIS_REPORT,
                        reportId
                )
                .map(save -> new SavedState(
                        true,
                        savedAt(save),
                        selectedItems(save)
                ))
                .orElseGet(SavedState::unsaved);
    }

    // 마이 클로젯 목록에 표시할 분석 리포트 정보 조회
    @Transactional(readOnly = true)
    public Map<Long, ClosetReportView> findClosetViews(List<Long> reportIds, Long memberId) {

        if (reportIds.isEmpty()) {
            return Map.of();
        }

        // 저장 후 삭제된 리포트는 조회되지 않아 호출측 목록에서 빠짐
        // 전용 목록 getSavedReports 와 달리 통합 목록이라 예외 대신 제외 처리
        return analysisReportRepository
                .findByIdInAndMemberIdAndDeletedAtIsNull(reportIds, memberId)
                .stream()
                .collect(Collectors.toMap(
                        AnalysisReport::getId,
                        report -> new ClosetReportView(
                                resolveImageUrl(report),
                                recommendationTagNames(report)
                        )
                ));
    }

    private SaveOutcome createSave(
            AnalysisReport report,
            AnalysisReportSaveRequest request
    ) {
        List<RecommendedItem> recommendedItems = recommendedItemRepository
                .findByReportIdOrderByCategoryAscRankNoAsc(report.getId());
        validateRecommendationReady(report, recommendedItems);
        List<RecommendedItem> selections = resolveSelections(request, recommendedItems);

        ClosetSave save = closetSaveRepository.saveAndFlush(ClosetSave.create(
                report.getMember(),
                ClosetTargetType.ANALYSIS_REPORT,
                report.getId()
        ));
        entityManager.refresh(save);
        List<SavedAnalysisItem> savedItems = savedAnalysisItemRepository.saveAll(
                selections.stream()
                        .map(item -> SavedAnalysisItem.from(save, item))
                        .toList()
        );
        return new SaveOutcome(
                new AnalysisReportSaveResponse(
                        report.getId(),
                        true,
                        savedAt(save),
                        savedItems.stream()
                                .map(SavedAnalysisItemResponse::from)
                                .toList()
                ),
                true
        );
    }

    private void validateRecommendationReady(
            AnalysisReport report,
            List<RecommendedItem> recommendedItems
    ) {
        boolean currentResult = report.getRecommendationGeneratedAt() != null
                && report.hasRecommendationInputRevision(report.getResultInputRevision());
        if (!currentResult || recommendedItems.isEmpty()) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
        }
    }

    private List<RecommendedItem> resolveSelections(
            AnalysisReportSaveRequest request,
            List<RecommendedItem> recommendedItems
    ) {
        Map<ProductCategory, AnalysisReportSaveRequest.SelectedItem> requestedByCategory =
                new EnumMap<>(ProductCategory.class);
        for (AnalysisReportSaveRequest.SelectedItem selectedItem : request.selectedItems()) {
            if (requestedByCategory.putIfAbsent(
                    selectedItem.category(),
                    selectedItem
            ) != null) {
                throw invalidSelection();
            }
        }

        Set<ProductCategory> availableCategories = recommendedItems.stream()
                .map(RecommendedItem::getCategory)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!availableCategories.equals(requestedByCategory.keySet())) {
            throw invalidSelection();
        }

        Map<SelectionKey, RecommendedItem> itemsBySelection = recommendedItems.stream()
                .collect(Collectors.toMap(
                        item -> new SelectionKey(
                                item.getCategory(),
                                item.getProduct().getId()
                        ),
                        Function.identity()
                ));
        return availableCategories.stream()
                .map(category -> {
                    AnalysisReportSaveRequest.SelectedItem requested =
                            requestedByCategory.get(category);
                    RecommendedItem selected = itemsBySelection.get(new SelectionKey(
                            category,
                            requested.productId()
                    ));
                    if (selected == null) {
                        throw invalidSelection();
                    }
                    return selected;
                })
                .toList();
    }

    private AnalysisSummaryResponse toSummary(AnalysisReport report, ClosetSave save) {
        return new AnalysisSummaryResponse(
                report.getId(),
                resolveImageUrl(report),
                recommendationTagNames(report),
                savedAt(save)
        );
    }

    private AnalysisReportSaveResponse toResponse(Long reportId, ClosetSave save) {
        return new AnalysisReportSaveResponse(
                reportId,
                true,
                savedAt(save),
                selectedItems(save)
        );
    }

    private LocalDateTime savedAt(ClosetSave save) {
        return save.getCreatedAt().truncatedTo(ChronoUnit.MICROS);
    }

    private List<SavedAnalysisItemResponse> selectedItems(ClosetSave save) {
        return savedAnalysisItemRepository
                .findByClosetSaveIdOrderByCategoryAsc(save.getId())
                .stream()
                .map(SavedAnalysisItemResponse::from)
                .toList();
    }

    private List<String> recommendationTagNames(AnalysisReport report) {
        Stream<String> knownTags = report.getDisplayTags().stream()
                .map(Tag::getTagName);
        Stream<String> customTags = report.getCustomTags().stream()
                .map(ReportCustomTag::getDisplayName);
        return Stream.concat(knownTags, customTags).toList();
    }

    private String resolveImageUrl(AnalysisReport report) {
        return report.getOriginalImage() == null
                ? report.getImageUrl()
                : imageUploadService.createReadUrl(report.getOriginalImage());
    }

    private int validatePageSize(Integer requestedPageSize) {
        int pageSize = requestedPageSize == null ? DEFAULT_PAGE_SIZE : requestedPageSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        return pageSize;
    }

    private BusinessException invalidSelection() {
        return new BusinessException(ErrorCode.ANALYSIS_SELECTION_INVALID);
    }

    public record SaveOutcome(AnalysisReportSaveResponse response, boolean created) {
    }

    public record SavedState(
            boolean saved,
            LocalDateTime savedAt,
            List<SavedAnalysisItemResponse> selectedItems
    ) {

        public SavedState {
            selectedItems = List.copyOf(selectedItems);
        }

        private static SavedState unsaved() {
            return new SavedState(false, null, List.of());
        }
    }

    // 마이 클로젯 목록 전달용, API 응답 DTO 아님
    public record ClosetReportView(
            String thumbnailUrl,
            List<String> tags
    ) {
    }

    private record SelectionKey(ProductCategory category, Long productId) {
    }
}
