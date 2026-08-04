package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiTagBlindEvaluationMainTest {

    @Test
    void preservesUnreportedTokenUsageAsNull() {
        AiTagModelResult result = new AiTagModelResult(
                "provider",
                "model",
                List.of(new AiTagGarment(
                        GarmentPiece.TOP,
                        List.of(new AiTagPrediction(
                                com.fitback.backend.domain.tag.entity.TagType.STYLE,
                                "캐주얼"
                        )),
                        List.of()
                )),
                null,
                null,
                10
        );

        Map<String, Object> evaluation = AiTagBlindEvaluationMain.successfulEvaluation(result);

        assertThat(evaluation).containsKeys("inputTokens", "outputTokens");
        assertThat(evaluation.get("inputTokens")).isNull();
        assertThat(evaluation.get("outputTokens")).isNull();
    }
}
