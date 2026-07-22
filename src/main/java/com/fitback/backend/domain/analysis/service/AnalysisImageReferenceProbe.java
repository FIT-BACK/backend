package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.image.service.ImageReferenceProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisImageReferenceProbe implements ImageReferenceProbe {

    private final AnalysisReportRepository analysisReportRepository;

    @Override
    public boolean exists(String imageId) {
        return analysisReportRepository.existsByOriginalImageId(imageId);
    }
}
