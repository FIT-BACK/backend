package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.analysis.dto.AnalysisByImageRequest;
import com.fitback.backend.domain.analysis.dto.AnalysisCreateResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisDetailResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisListResponse;
import com.fitback.backend.domain.analysis.dto.SuggestedTagResponse;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.entity.ReportCustomTag;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import com.fitback.backend.domain.image.service.ImageUploadService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final int DEFAULT_MATCH_PERCENTAGE = 50;

    private final AnalysisReportRepository analysisReportRepository;
    private final MemberRepository memberRepository;
    private final ImageStorage imageStorage;
    private final AiTagAnalyzer aiTagAnalyzer;
    private final RecommendationResultProvider recommendationResultProvider;
    private final ImageUploadService imageUploadService;
    private final AnalysisReportSaveService analysisReportSaveService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public AnalysisCreateResponse create(Long memberId, MultipartFile image) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String imageUrl = imageStorage.store(image);
        boolean rollbackCleanupRegistered = registerRollbackCleanup(imageUrl);
        try {
            AiTagAnalysisResult analysisResult = aiTagAnalyzer.analyze(image);

            AnalysisReport report = AnalysisReport.create(
                    member,
                    imageUrl,
                    DEFAULT_MATCH_PERCENTAGE,
                    analysisResult.garmentPiece().orElse(null)
            );
            analysisResult.canonicalTags().forEach(report::addAiSuggestedTag);
            AnalysisReport savedReport = analysisReportRepository.save(report);

            List<SuggestedTagResponse> tagResponses = savedReport.getReportTags().stream()
                    .map(reportTag -> new SuggestedTagResponse(
                            reportTag.getTag().getId(),
                            reportTag.getTag().getTagName()
                    ))
                    .toList();
            return new AnalysisCreateResponse(
                    savedReport.getId(),
                    savedReport.getImageUrl(),
                    savedReport.getMatchPercentage(),
                    tagResponses
            );
        } catch (RuntimeException exception) {
            if (!rollbackCleanupRegistered) {
                deleteStoredImage(imageUrl);
            }
            throw exception;
        }
    }

    @Transactional
    public AnalysisCreateResponse create(Long memberId, AnalysisByImageRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // READY 이미지의 소유권과 목적을 검증하고 ACTIVE로 바꾼 뒤 리포트에 연결한다.
        Image image = imageUploadService.activateAnalysisImage(
                memberId,
                request.imageId()
        );
        AiTagAnalysisResult analysisResult = aiTagAnalyzer.analyze(image);

        AnalysisReport report = AnalysisReport.create(
                member,
                image,
                DEFAULT_MATCH_PERCENTAGE,
                analysisResult.garmentPiece().orElse(null)
        );
        analysisResult.canonicalTags().forEach(report::addAiSuggestedTag);
        AnalysisReport savedReport = analysisReportRepository.save(report);
        return toCreateResponse(savedReport);
    }

    @Transactional(readOnly = true)
    public AnalysisListResponse getReports(Long memberId, Long cursor, Integer requestedPageSize) {
        return analysisReportSaveService.getSavedReports(
                memberId,
                cursor,
                requestedPageSize
        );
    }

    @Transactional(readOnly = true)
    public AnalysisDetailResponse getReport(Long memberId, Long reportId) {
        AnalysisReport report = findOwnedReport(memberId, reportId);
        return toDetailResponse(report, recommendationResultProvider.findFor(report));
    }

    @Transactional
    public void deleteReport(Long memberId, Long reportId) {
        // 화면에서는 즉시 숨기되 관계 데이터 보존을 위해 리포트 행은 soft delete한다.
        AnalysisReport report = findOwnedReport(memberId, reportId);
        report.softDelete(clock.instant());
        if (report.getOriginalImage() != null) {
            eventPublisher.publishEvent(new ImageReferencesReleasedEvent(
                    List.of(report.getOriginalImage().getId())
            ));
        }
    }

    private AnalysisReport findOwnedReport(Long memberId, Long reportId) {
        return analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(reportId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND));
    }

    private AnalysisDetailResponse toDetailResponse(
            AnalysisReport report,
            RecommendationResultResponse recommendationResult
    ) {
        List<String> tagNames = recommendationTagNames(report);
        AnalysisReportSaveService.SavedState savedState =
                analysisReportSaveService.getState(report.getMember().getId(), report.getId());
        return new AnalysisDetailResponse(
                report.getId(),
                report.getOriginalImage() == null ? null : report.getOriginalImage().getId(),
                resolveImageUrl(report),
                report.getMatchPercentage(),
                tagNames,
                recommendationResult.recommendationStatus(),
                recommendationResult.scoreVersion(),
                recommendationResult.recommendationGroups(),
                savedState.saved(),
                savedState.savedAt(),
                savedState.selectedItems()
        );
    }

    private List<String> recommendationTagNames(AnalysisReport report) {
        List<String> tagNames = new ArrayList<>();
        report.getDisplayTags().stream()
                .map(Tag::getTagName)
                .forEach(tagNames::add);
        report.getCustomTags().stream()
                .map(ReportCustomTag::getDisplayName)
                .forEach(tagNames::add);
        return List.copyOf(tagNames);
    }

    private AnalysisCreateResponse toCreateResponse(AnalysisReport report) {
        List<SuggestedTagResponse> tagResponses = report.getReportTags().stream()
                .map(reportTag -> new SuggestedTagResponse(
                        reportTag.getTag().getId(),
                        reportTag.getTag().getTagName()
                ))
                .toList();
        return new AnalysisCreateResponse(
                report.getId(),
                resolveImageUrl(report),
                report.getMatchPercentage(),
                tagResponses
        );
    }

    private String resolveImageUrl(AnalysisReport report) {
        return report.getOriginalImage() == null
                ? report.getImageUrl()
                : imageUploadService.createReadUrl(report.getOriginalImage());
    }

    private boolean registerRollbackCleanup(String imageUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        // 커밋 단계의 DB 실패까지 포함해 롤백된 multipart 파일만 제거한다.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            deleteStoredImage(imageUrl);
                        }
                    }
                }
        );
        return true;
    }

    private void deleteStoredImage(String imageUrl) {
        try {
            imageStorage.delete(imageUrl);
        } catch (RuntimeException cleanupException) {
            log.warn("Failed to clean up rolled back analysis image. imageUrl={}", imageUrl,
                    cleanupException);
        }
    }
}
