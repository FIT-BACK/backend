package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.analysis.dto.AnalysisByImageRequest;
import com.fitback.backend.domain.analysis.dto.AnalysisCreateResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisDetailResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisListResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisSummaryResponse;
import com.fitback.backend.domain.analysis.dto.ConfirmTagsRequest;
import com.fitback.backend.domain.analysis.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.analysis.dto.SuggestedTagResponse;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.image.service.ImageAssetService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final int DEFAULT_MATCH_PERCENTAGE = 70;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final AnalysisReportRepository analysisReportRepository;
    private final MemberRepository memberRepository;
    private final TagRepository tagRepository;
    private final ImageStorage imageStorage;
    private final AiTagAnalyzer aiTagAnalyzer;
    private final RecommendationResultProvider recommendationResultProvider;
    private final ImageAssetService imageAssetService;
    private final Clock clock;

    @Transactional
    public AnalysisCreateResponse create(Long memberId, MultipartFile image) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String imageUrl = imageStorage.store(image);
        List<Tag> suggestedTags = aiTagAnalyzer.analyze(image);

        AnalysisReport report = AnalysisReport.create(member, imageUrl, DEFAULT_MATCH_PERCENTAGE);
        suggestedTags.forEach(report::addAiSuggestedTag);
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
    }

    @Transactional
    public AnalysisCreateResponse create(Long memberId, AnalysisByImageRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // READY 이미지의 소유권과 목적을 검증하고 ACTIVE로 바꾼 뒤 리포트에 연결한다.
        ImageAsset imageAsset = imageAssetService.activateAnalysisImage(
                memberId,
                request.imageId()
        );
        List<Tag> suggestedTags = aiTagAnalyzer.analyze(imageAsset);

        AnalysisReport report = AnalysisReport.create(
                member,
                imageAsset,
                DEFAULT_MATCH_PERCENTAGE
        );
        suggestedTags.forEach(report::addAiSuggestedTag);
        AnalysisReport savedReport = analysisReportRepository.save(report);
        return toCreateResponse(savedReport);
    }

    @Transactional(readOnly = true)
    public AnalysisListResponse getReports(Long memberId, Long cursor, Integer requestedPageSize) {
        int pageSize = validatePageSize(requestedPageSize);
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        PageRequest pageRequest = PageRequest.of(0, pageSize);
        Slice<AnalysisReport> reports = cursor == null
                ? analysisReportRepository.findByMemberIdAndDeletedAtIsNullOrderByIdDesc(
                        memberId,
                        pageRequest
                )
                : analysisReportRepository
                        .findByMemberIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                        memberId,
                        cursor,
                        pageRequest
                );
        List<AnalysisSummaryResponse> items = reports.getContent().stream()
                .map(this::toSummaryResponse)
                .toList();
        Long nextCursor = reports.hasNext() && !items.isEmpty()
                ? items.get(items.size() - 1).reportId()
                : null;
        return new AnalysisListResponse(items, nextCursor, reports.hasNext(), pageSize);
    }

    @Transactional(readOnly = true)
    public AnalysisDetailResponse getReport(Long memberId, Long reportId) {
        AnalysisReport report = findOwnedReport(memberId, reportId);
        return toDetailResponse(report, recommendationResultProvider.findByReportId(reportId));
    }

    @Transactional
    public AnalysisDetailResponse confirmTags(
            Long memberId,
            Long reportId,
            ConfirmTagsRequest request
    ) {
        AnalysisReport report = findOwnedReport(memberId, reportId);
        List<Tag> confirmedTags = findTagsInRequestOrder(request.confirmedTagIds());
        report.confirmTags(confirmedTags, request.matchPercentage());
        List<RecommendationGroupResponse> recommendationGroups =
                recommendationResultProvider.generateFor(report);
        return toDetailResponse(report, recommendationGroups);
    }

    @Transactional
    public void deleteReport(Long memberId, Long reportId) {
        // 화면에서는 즉시 숨기되 관계 데이터 보존을 위해 리포트 행은 soft delete한다.
        findOwnedReport(memberId, reportId).softDelete(clock.instant());
    }

    private AnalysisReport findOwnedReport(Long memberId, Long reportId) {
        return analysisReportRepository.findByIdAndMemberIdAndDeletedAtIsNull(reportId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND));
    }

    private List<Tag> findTagsInRequestOrder(List<Long> requestedTagIds) {
        if (requestedTagIds == null || requestedTagIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        Set<Long> uniqueTagIds = new LinkedHashSet<>(requestedTagIds);
        Map<Long, Tag> tagsById = new LinkedHashMap<>();
        tagRepository.findAllById(uniqueTagIds).forEach(tag -> tagsById.put(tag.getId(), tag));
        if (tagsById.size() != uniqueTagIds.size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        return uniqueTagIds.stream().map(tagsById::get).toList();
    }

    private AnalysisSummaryResponse toSummaryResponse(AnalysisReport report) {
        List<String> tagNames = report.getDisplayTags().stream()
                .map(Tag::getTagName)
                .toList();
        return new AnalysisSummaryResponse(report.getId(), resolveImageUrl(report), tagNames);
    }

    private AnalysisDetailResponse toDetailResponse(
            AnalysisReport report,
            List<RecommendationGroupResponse> recommendationGroups
    ) {
        List<String> tagNames = report.getDisplayTags().stream()
                .map(Tag::getTagName)
                .toList();
        return new AnalysisDetailResponse(
                report.getId(),
                resolveImageUrl(report),
                report.getMatchPercentage(),
                tagNames,
                recommendationGroups
        );
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
                : imageAssetService.createReadUrl(report.getOriginalImage());
    }

    private int validatePageSize(Integer requestedPageSize) {
        int pageSize = requestedPageSize == null ? DEFAULT_PAGE_SIZE : requestedPageSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        return pageSize;
    }
}
