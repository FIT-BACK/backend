package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CanonicalAiTagAnalyzerTest {

    private final TagRepository tagRepository = mock(TagRepository.class);
    private final Image image = mock(Image.class);
    private final AiTagImage content = new AiTagImage(new byte[]{1}, "image/jpeg");
    private final List<Tag> catalog = List.of(
            Tag.create("캐주얼", TagType.STYLE),
            Tag.create("데님", TagType.MATERIAL),
            Tag.create("와이드핏", TagType.SILHOUETTE)
    );

    @BeforeEach
    void setUp() {
        when(tagRepository.findAllByOrderByIdAsc()).thenReturn(catalog);
    }

    @Test
    void mapsProviderPredictionsToCanonicalTagsThroughOneAnalyzerContract() {
        AiTagModelClient client = (ignoredImage, request) -> new AiTagModelResult(
                "test",
                "test-model",
                List.of(
                        new AiTagPrediction(TagType.STYLE, "캐주얼"),
                        new AiTagPrediction(TagType.MATERIAL, "데님")
                ),
                List.of(new AiTagSuggestion(
                        TagType.COLOR,
                        "인디고 블루",
                        0.93,
                        "짙은 청색 표면"
                )),
                1,
                1,
                1
        );
        CanonicalAiTagAnalyzer analyzer = analyzer(client);

        assertThat(analyzer.analyze(image))
                .extracting(Tag::getTagName)
                .containsExactly("캐주얼", "데님");
    }

    @Test
    void rejectsAValidNamePairedWithTheWrongType() {
        AiTagModelClient client = (ignoredImage, request) -> new AiTagModelResult(
                "test",
                "test-model",
                List.of(new AiTagPrediction(TagType.DETAIL, "데님")),
                null,
                null,
                1
        );

        assertThatThrownBy(() -> analyzer(client).analyze(image))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_NOT_READY);
    }

    @Test
    void requestSchemaContainsStyleAndMaterialCatalogValues() {
        AiTagModelRequest request = new AiTagRequestFactory().create(catalog);

        assertThat(request.prompt()).contains("STYLE: 캐주얼", "MATERIAL: 데님");
        assertThat(request.jsonSchema().toString()).contains("STYLE", "MATERIAL", "데님");
    }

    private CanonicalAiTagAnalyzer analyzer(AiTagModelClient client) {
        return new CanonicalAiTagAnalyzer(
                tagRepository,
                ignored -> content,
                new AiTagRequestFactory(),
                client
        );
    }
}
