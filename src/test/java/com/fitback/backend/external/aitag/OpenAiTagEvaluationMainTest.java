package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.TagType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

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

    @Test
    void recordsOnlySafeFailureMetadataAndBase64ImageLength() {
        OpenAiTagEvaluationMain.EvaluationCase evaluationCase = new OpenAiTagEvaluationMain.EvaluationCase(
                "top-01", "images/top-01.jpeg", List.of(
                        new AiTagPrediction(TagType.STYLE, "캐주얼")
                ));
        OpenAiTagEvaluationMain.CaseResult result = OpenAiTagEvaluationMain.CaseResult.failed(
                evaluationCase,
                "ANALYSIS409_1",
                321L,
                500,
                "SERVER_ERROR",
                null,
                OpenAiTagEvaluationMain.base64ImageLength(4)
        );

        assertThat(result.imageId()).isEqualTo("top-01");
        assertThat(result.elapsedMillis()).isEqualTo(321L);
        assertThat(result.providerHttpStatus()).isEqualTo(500);
        assertThat(result.providerErrorCategory()).isEqualTo("SERVER_ERROR");
        assertThat(result.responseParsingCategory()).isNull();
        assertThat(result.base64ImageLength()).isEqualTo(8L);
        assertThat(result.error()).isEqualTo("ANALYSIS409_1");
    }

    @Test
    void calculatesBase64LengthWithoutEncodingImageBytes() {
        assertThat(OpenAiTagEvaluationMain.base64ImageLength(0)).isZero();
        assertThat(OpenAiTagEvaluationMain.base64ImageLength(1)).isEqualTo(4L);
        assertThat(OpenAiTagEvaluationMain.base64ImageLength(2)).isEqualTo(4L);
        assertThat(OpenAiTagEvaluationMain.base64ImageLength(3)).isEqualTo(4L);
        assertThat(OpenAiTagEvaluationMain.base64ImageLength(4)).isEqualTo(8L);
    }

    @Test
    void rejectsDatasetPropertiesOutsideTheGoldLabelSchema(@TempDir Path directory) throws Exception {
        Path dataset = directory.resolve("gold-labels.json");
        Files.writeString(dataset, """
                {
                  "cases": [{
                    "imageId": "top-01",
                    "imagePath": "images/top-01.jpg",
                    "expectedCanonicalTags": [{"type": "STYLE", "name": "캐주얼"}]
                  }],
                  "unexpected": true
                }
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> OpenAiTagEvaluationMain.readDataset(dataset)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset contains unsupported fields");
    }

    @Test
    void rejectsImageSymlinkThatResolvesOutsideTheDataset(@TempDir Path directory) throws Exception {
        Path datasetDirectory = Files.createDirectory(directory.resolve("dataset"));
        Path imagesDirectory = Files.createDirectory(datasetDirectory.resolve("images"));
        Path externalImage = Files.write(directory.resolve("outside.jpg"), new byte[]{1});
        Files.createSymbolicLink(imagesDirectory.resolve("linked.jpg"), externalImage);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> OpenAiTagEvaluationMain.resolveImage(datasetDirectory, "images/linked.jpg")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("imagePath must remain within the dataset directory");
    }

    @Test
    void documentsGoldTagUniquenessInTheSchema() throws Exception {
        Path schema = Path.of("scripts/poc/ai-tag-evaluation/gold-labels.schema.json");
        assertThat(new ObjectMapper().readTree(Files.readString(schema))
                .path("properties").path("cases").path("items").path("properties")
                .path("expectedCanonicalTags").path("uniqueItems").asBoolean()).isTrue();
    }
}
