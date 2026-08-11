package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.image.entity.Image;
import org.springframework.web.multipart.MultipartFile;

public interface AiTagAnalyzer {

    AiTagAnalysisResult analyze(MultipartFile image);

    AiTagAnalysisResult analyze(Image image);
}
