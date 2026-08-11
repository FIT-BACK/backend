package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.external.aitag.GarmentPiece;
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
        return AiTagAnalysisResult.withGarmentPiece(
                GarmentPiece.TOP,
                tagRepository.findTop3ByOrderByIdAsc()
        );
    }

    @Override
    public AiTagAnalysisResult analyze(Image image) {
        return AiTagAnalysisResult.withGarmentPiece(
                GarmentPiece.TOP,
                tagRepository.findTop3ByOrderByIdAsc()
        );
    }
}
