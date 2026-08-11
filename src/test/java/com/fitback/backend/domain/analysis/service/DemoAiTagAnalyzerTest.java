package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.external.aitag.GarmentPiece;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class DemoAiTagAnalyzerTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private MultipartFile multipartFile;

    @Mock
    private Image image;

    @Test
    void returnsTopGarmentPieceForBothAnalysisFlows() {
        List<Tag> tags = List.of(Tag.create("미니멀", TagType.STYLE));
        when(tagRepository.findTop3ByOrderByIdAsc()).thenReturn(tags);
        DemoAiTagAnalyzer analyzer = new DemoAiTagAnalyzer(tagRepository);

        AiTagAnalysisResult multipartResult = analyzer.analyze(multipartFile);
        AiTagAnalysisResult uploadedImageResult = analyzer.analyze(image);

        assertThat(multipartResult.garmentPiece()).contains(GarmentPiece.TOP);
        assertThat(multipartResult.canonicalTags()).containsExactlyElementsOf(tags);
        assertThat(uploadedImageResult.garmentPiece()).contains(GarmentPiece.TOP);
        assertThat(uploadedImageResult.canonicalTags()).containsExactlyElementsOf(tags);
    }
}
