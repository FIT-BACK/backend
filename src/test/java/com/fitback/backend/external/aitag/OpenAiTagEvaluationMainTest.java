package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiTagEvaluationMainTest {

    @Test
    void reportsSetMetricsUnknownCanonicalOutputLatencyAndTokens() {
        OpenAiTagEvaluationMain.EvaluationCase evaluationCase = new OpenAiTagEvaluationMain.EvaluationCase(
                "bottom-01", "images/bottom-01.jpeg", List.of(
                        new AiTagPrediction(TagType.STYLE, "캐주얼"),
                        new AiTagPrediction(TagType.MATERIAL, "데님")));
        AiTagModelResult result = new AiTagModelResult("openai", "gpt-5.6-luna", List.of(
                new AiTagGarment(GarmentPiece.BOTTOM, List.of(
                        new AiTagPrediction(TagType.STYLE, "캐주얼"),
                        new AiTagPrediction(TagType.COLOR, "블랙")), List.of())), 120, 30, 150);
        Set<OpenAiTagEvaluationMain.TagKey> catalog = Set.of(
                new OpenAiTagEvaluationMain.TagKey(TagType.STYLE, "캐주얼"),
                new OpenAiTagEvaluationMain.TagKey(TagType.MATERIAL, "데님"));

        OpenAiTagEvaluationMain.CaseResult caseResult = OpenAiTagEvaluationMain.successfulCase(
                evaluationCase, result, catalog);
        OpenAiTagEvaluationMain.EvaluationSummary summary = OpenAiTagEvaluationMain.summarize(List.of(caseResult));

        assertThat(caseResult.falseNegatives()).containsExactly(
                new OpenAiTagEvaluationMain.TagKey(TagType.MATERIAL, "데님"));
        assertThat(caseResult.falsePositives()).containsExactly(
                new OpenAiTagEvaluationMain.TagKey(TagType.COLOR, "블랙"));
        assertThat(caseResult.unknownCanonicalTags()).containsExactly(
                new OpenAiTagEvaluationMain.TagKey(TagType.COLOR, "블랙"));
        assertThat(summary.micro()).isEqualTo(new OpenAiTagEvaluationMain.Metrics(0.5, 0.5, 0.5));
        assertThat(summary.macro()).isEqualTo(new OpenAiTagEvaluationMain.Metrics(0.5, 0.5, 0.5));
        assertThat(summary.exactMatchCases()).isZero();
        assertThat(summary.exactMatchRate()).isZero();
        assertThat(summary.falseNegatives()).isEqualTo(new OpenAiTagEvaluationMain.FailureTags(1, List.of(
                new OpenAiTagEvaluationMain.TagCount(
                        new OpenAiTagEvaluationMain.TagKey(TagType.MATERIAL, "데님"), 1)
        )));
        assertThat(summary.falsePositives()).isEqualTo(new OpenAiTagEvaluationMain.FailureTags(1, List.of(
                new OpenAiTagEvaluationMain.TagCount(
                        new OpenAiTagEvaluationMain.TagKey(TagType.COLOR, "블랙"), 1)
        )));
        assertThat(summary.unknownCanonicalTags()).isEqualTo(new OpenAiTagEvaluationMain.FailureTags(1, List.of(
                new OpenAiTagEvaluationMain.TagCount(
                        new OpenAiTagEvaluationMain.TagKey(TagType.COLOR, "블랙"), 1)
        )));
        assertThat(summary.latency()).isEqualTo(new OpenAiTagEvaluationMain.Latency(150L, 150L, 150.0));
        assertThat(summary.tokens().input()).isEqualTo(new OpenAiTagEvaluationMain.TokenTotal(1, 120L, 120.0));
        assertThat(summary.tokens().output()).isEqualTo(new OpenAiTagEvaluationMain.TokenTotal(1, 30L, 30.0));
    }

    @Test
    void includesFailedCasesAsFalseNegativesInBaselineMetrics() {
        OpenAiTagEvaluationMain.EvaluationCase evaluationCase = new OpenAiTagEvaluationMain.EvaluationCase(
                "top-01", "images/top-01.jpeg", List.of(
                        new AiTagPrediction(TagType.STYLE, "캐주얼")
                ));

        OpenAiTagEvaluationMain.EvaluationSummary summary = OpenAiTagEvaluationMain.summarize(List.of(
                OpenAiTagEvaluationMain.CaseResult.failed(evaluationCase, "ANALYSIS409_1")
        ));

        assertThat(summary.micro()).isEqualTo(new OpenAiTagEvaluationMain.Metrics(0.0, 0.0, 0.0));
        assertThat(summary.macro()).isEqualTo(new OpenAiTagEvaluationMain.Metrics(0.0, 0.0, 0.0));
        assertThat(summary.exactMatchRate()).isZero();
        assertThat(summary.falseNegatives().count()).isEqualTo(1);
        assertThat(summary.latency()).isEqualTo(new OpenAiTagEvaluationMain.Latency(null, null, null));
    }
}
