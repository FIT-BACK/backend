package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@Profile({"default", "local", "test"})
@RequiredArgsConstructor
public class DemoAiTagAnalyzer implements AiTagAnalyzer {

    private final TagRepository tagRepository;

    @Override
    public AiTagAnalysisResult analyze(MultipartFile image) {
        return AiTagAnalysisResult.withoutGarmentPiece(
                tagRepository.findTop3ByOrderByIdAsc()
        );
    }

    @Override
    public AiTagAnalysisResult analyze(Image image) {
        return AiTagAnalysisResult.withoutGarmentPiece(
                tagRepository.findTop3ByOrderByIdAsc()
        );
    }
}
