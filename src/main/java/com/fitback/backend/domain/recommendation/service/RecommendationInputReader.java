package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationInputReader {

    private final AnalysisReportRepository analysisReportRepository;

    public RecommendationInputReader(AnalysisReportRepository analysisReportRepository) {
        this.analysisReportRepository = analysisReportRepository;
    }

    @Transactional(readOnly = true)
    public RecommendationInputSnapshot read(Long memberId, Long reportId) {
        AnalysisReport report = analysisReportRepository
                .findByIdAndMemberIdAndDeletedAtIsNull(reportId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND));
        List<TagInput> tags = report.getDisplayTags().stream()
                .sorted(Comparator.comparing(Tag::getId).thenComparing(Tag::getTagName))
                .map(tag -> new TagInput(tag.getId(), tag.getTagName()))
                .toList();
        if (tags.isEmpty()) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
        }
        return new RecommendationInputSnapshot(
                report.getId(),
                memberId,
                report.getRecommendationInputRevision(),
                tags
        );
    }
}
