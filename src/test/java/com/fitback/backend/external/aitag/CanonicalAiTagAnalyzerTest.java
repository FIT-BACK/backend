package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.service.AiTagAnalysisResult;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagTargetClothing;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

class CanonicalAiTagAnalyzerTest {

    private final TagRepository tagRepository = mock(TagRepository.class);
    private final Image image = mock(Image.class);
    private final AiTagImage content = new AiTagImage(new byte[]{1}, "image/jpeg");
    private final List<Tag> catalog = List.of(
            Tag.create("캐주얼", TagType.STYLE, List.of(TagTargetClothing.ALL)),
            Tag.create("데님", TagType.MATERIAL, List.of(TagTargetClothing.ALL)),
            Tag.create("와이드핏", TagType.SILHOUETTE, List.of(TagTargetClothing.PANTS))
    );

    @BeforeEach
    void setUp() {
        when(tagRepository.findAllByOrderByIdAsc()).thenReturn(catalog);
    }

    @ParameterizedTest
    @EnumSource(GarmentPiece.class)
    void returnsEveryGarmentPieceWithResolvedCanonicalTags(GarmentPiece garmentPiece) {
        AiTagModelClient client = (ignoredImage, request) -> new AiTagModelResult(
                "test",
                "test-model",
                List.of(new AiTagGarment(
                        garmentPiece,
                        List.of(
                                new AiTagPrediction(TagType.STYLE, "캐주얼"),
                                new AiTagPrediction(TagType.MATERIAL, "데님")
                        ),
                        List.of(new AiTagSuggestion(
                                TagType.COLOR,
                                "인디고 블루",
                                0.93,
                                "하의의 짙은 청색 표면"
                        ))
                )),
                1,
                1,
                1
        );
        CanonicalAiTagAnalyzer analyzer = analyzer(client);

        AiTagAnalysisResult result = analyzer.analyze(image);

        assertThat(result.garmentPiece()).contains(garmentPiece);
        assertThat(result.canonicalTags())
                .extracting(Tag::getTagName)
                .containsExactly("캐주얼", "데님");
    }

    @Test
    void rejectsAValidNamePairedWithTheWrongType() {
        AtomicInteger attempts = new AtomicInteger();
        AiTagModelClient client = (ignoredImage, request) -> new AiTagModelResult(
                "test",
                "test-model",
                List.of(new AiTagGarment(
                        GarmentPiece.BOTTOM,
                        List.of(new AiTagPrediction(TagType.DETAIL, "데님")),
                        List.of()
                )),
                null,
                null,
                1
        );
        AiTagModelClient countingClient = (ignoredImage, request) -> {
            attempts.incrementAndGet();
            return client.analyze(ignoredImage, request);
        };

        Logger logger = (Logger) LoggerFactory.getLogger(CanonicalAiTagAnalyzer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> analyzer(countingClient).analyze(image))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ErrorCode.ANALYSIS_NOT_READY);

            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("provider=test", "model=test-model")
                    .contains("canonicalValidationCategory=UNKNOWN_CANONICAL_TAG")
                    .contains("predictedTagCount=1", "catalogTagCount=3", "elapsedMillis=1")
                    .doesNotContain("데님");
            assertThat(attempts).hasValue(1);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rejectsSuggestedTagsOnlyBecauseAnalysisRequiresCanonicalMasterTag() {
        AiTagModelClient client = (ignoredImage, request) -> new AiTagModelResult(
                "test",
                "test-model",
                List.of(new AiTagGarment(
                        GarmentPiece.DRESS,
                        List.of(),
                        List.of(new AiTagSuggestion(
                                TagType.COLOR,
                                "인디고 블루",
                                0.93,
                                "원피스의 짙은 청색 표면"
                        ))
                )),
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

    @Test
    void rejectsMultipleGarmentsBeforeCanonicalResolutionCanFlattenOwnership() {
        assertThatThrownBy(() -> new AiTagModelResult(
                "test",
                "test-model",
                List.of(
                        new AiTagGarment(
                                GarmentPiece.TOP,
                                List.of(new AiTagPrediction(TagType.STYLE, "캐주얼")),
                                List.of()
                        ),
                        new AiTagGarment(
                                GarmentPiece.BOTTOM,
                                List.of(new AiTagPrediction(TagType.MATERIAL, "데님")),
                                List.of()
                        )
                ),
                null,
                null,
                1
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("garments must contain exactly 1 item");
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
