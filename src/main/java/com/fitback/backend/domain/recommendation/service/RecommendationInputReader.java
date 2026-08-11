package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationInputReader {

    private final AnalysisReportRepository analysisReportRepository;
    private final RecommendationInputSnapshotFactory snapshotFactory;

    @Transactional(readOnly = true)
    public RecommendationInputSnapshot read(Long memberId, Long reportId) {
        AnalysisReport report = analysisReportRepository
                .findByIdAndMemberIdAndDeletedAtIsNull(reportId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND));
        RecommendationInputSnapshot snapshot = snapshotFactory.from(report, memberId);
        if (snapshot.category() == null
                || (snapshot.tags().isEmpty() && snapshot.customTagNames().isEmpty())) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
        }
        return snapshot;
    }
}
