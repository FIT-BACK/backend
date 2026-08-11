package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.recommendation.dto.RecommendationGenerateRequest;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationInputCommandService {

    private final AnalysisReportRepository analysisReportRepository;
    private final TagRepository tagRepository;
    private final RecommendationInputSnapshotFactory snapshotFactory;

    @Transactional
    public RecommendationInputSnapshot confirmAndRead(
            Long memberId,
            Long reportId,
            RecommendationGenerateRequest request
    ) {
        AnalysisReport report = analysisReportRepository
                .findOwnedReportForRecommendationUpdate(reportId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND));
        if (report.getGarmentPiece() == null) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
        }
        List<Tag> tags = tagRepository.findAllById(request.confirmedTagIds());
        if (tags.size() != request.confirmedTagIds().size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        report.confirmRecommendationInput(
                tags,
                request.customTagNames(),
                request.matchPercentage()
        );
        return snapshotFactory.from(report, memberId);
    }
}
