package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrototypeAiTagAnalyzerTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private Image image;

    @Test
    void returnsCanonicalPrototypeTagsInStableOrder() {
        Tag minimal = Tag.create("미니멀", TagType.STYLE);
        Tag wideFit = Tag.create("와이드핏", TagType.SILHOUETTE);
        Tag beige = Tag.create("베이지", TagType.COLOR);
        when(tagRepository.findAllByTagNameIn(List.of("미니멀", "와이드핏", "베이지")))
                .thenReturn(List.of(beige, minimal, wideFit));

        PrototypeAiTagAnalyzer analyzer = new PrototypeAiTagAnalyzer(tagRepository);

        assertThat(analyzer.analyze(image))
                .extracting(Tag::getTagName)
                .containsExactly("미니멀", "와이드핏", "베이지");
    }

    @Test
    void rejectsAnalysisWhenCanonicalTagsAreMissing() {
        when(tagRepository.findAllByTagNameIn(List.of("미니멀", "와이드핏", "베이지")))
                .thenReturn(List.of(Tag.create("미니멀", TagType.DETAIL)));

        PrototypeAiTagAnalyzer analyzer = new PrototypeAiTagAnalyzer(tagRepository);

        assertThatThrownBy(() -> analyzer.analyze(image))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_NOT_READY);
    }
}
