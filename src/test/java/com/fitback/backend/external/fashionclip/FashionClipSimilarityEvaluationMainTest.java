package com.fitback.backend.external.fashionclip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FashionClipSimilarityEvaluationMainTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void readsOneQueryAndTenHumanLabeledCandidates() throws Exception {
        FashionClipSimilarityEvaluationMain.Dataset dataset =
                FashionClipSimilarityEvaluationMain.readDataset(writeDataset(validDatasetJson(10)));

        assertThat(dataset.query()).isEqualTo(new FashionClipSimilarityEvaluationMain.QueryImage(
                "query-01", "images/query.jpg"));
        assertThat(dataset.candidates()).hasSize(10);
        assertThat(dataset.candidates()).extracting(
                FashionClipSimilarityEvaluationMain.CandidateImage::relationLabel)
                .containsExactly(
                        FashionClipSimilarityEvaluationMain.RelationLabel.NEAR_DUPLICATE,
                        FashionClipSimilarityEvaluationMain.RelationLabel.VISUALLY_SIMILAR,
                        FashionClipSimilarityEvaluationMain.RelationLabel.SAME_CATEGORY_DIFFERENT_DESIGN,
                        FashionClipSimilarityEvaluationMain.RelationLabel.UNRELATED,
                        FashionClipSimilarityEvaluationMain.RelationLabel.UNRELATED,
                        FashionClipSimilarityEvaluationMain.RelationLabel.UNRELATED,
                        FashionClipSimilarityEvaluationMain.RelationLabel.UNRELATED,
                        FashionClipSimilarityEvaluationMain.RelationLabel.UNRELATED,
                        FashionClipSimilarityEvaluationMain.RelationLabel.UNRELATED,
                        FashionClipSimilarityEvaluationMain.RelationLabel.UNRELATED);
    }

    @Test
    void writesFutureSimilarityAsNullWithoutComputingIt(@TempDir Path directory) throws Exception {
        FashionClipSimilarityEvaluationMain.Dataset dataset =
                FashionClipSimilarityEvaluationMain.readDataset(writeDataset(validDatasetJson(10)));
        Path output = directory.resolve("result.json");

        FashionClipSimilarityEvaluationMain.writeReport(output, dataset);

        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(output));
        assertThat(root.path("pairs")).hasSize(10);
        assertThat(root.path("pairs").get(0).path("relationLabel").asText())
                .isEqualTo("NEAR_DUPLICATE");
        assertThat(root.path("pairs").get(0).path("cosineSimilarity").isNull()).isTrue();
    }

    @Test
    void suppliedProviderFillsCosineSimilarityAndReceivesImageInputs(@TempDir Path directory) throws Exception {
        Path images = Files.createDirectory(directory.resolve("images"));
        Files.write(images.resolve("query.jpg"), new byte[]{1, 2});
        for (int index = 1; index <= 10; index++) {
            Files.write(images.resolve("candidate-%02d.jpg".formatted(index)), new byte[]{(byte) index});
        }
        FashionClipSimilarityEvaluationMain.Dataset dataset =
                FashionClipSimilarityEvaluationMain.readDataset(writeDataset(validDatasetJson(10)));
        List<String> inputOrder = new java.util.ArrayList<>();
        FashionClipEmbeddingProvider provider = new FashionClipEmbeddingProvider() {
            @Override
            public double[] embed(byte[] bytes, String contentType) {
                throw new AssertionError("single-image embed must not be used when batch is available");
            }

            @Override
            public List<double[]> embedBatch(List<FashionClipImageInput> inputs) {
                inputOrder.addAll(inputs.stream()
                        .map(input -> input.contentType() + ":" + input.imageBytes().length)
                        .toList());
                return inputs.stream()
                        .map(input -> input.imageBytes().length == 2 || input.imageBytes()[0] == 1
                                ? new double[]{1.0, 0.0}
                                : new double[]{0.0, 1.0})
                        .toList();
            }
        };
        Path output = directory.resolve("result-with-similarity.json");

        FashionClipSimilarityEvaluationMain.writeReport(output, dataset, provider, directory);

        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(output));
        assertThat(root.path("pairs").get(0).path("cosineSimilarity").asDouble()).isEqualTo(1.0);
        assertThat(root.path("pairs").get(1).path("cosineSimilarity").asDouble()).isEqualTo(0.0);
        assertThat(inputOrder).startsWith("image/jpeg:2", "image/jpeg:1", "image/jpeg:1");
    }

    @Test
    void rejectsCandidateCountOutsidePoCA() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> FashionClipSimilarityEvaluationMain.readDataset(writeDataset(validDatasetJson(9))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidates must contain 10 to 20 items");
    }

    @Test
    void rejectsDuplicateIdsAndQueryCollision() throws Exception {
        String duplicate = validDatasetJson(10).replace("candidate-02", "candidate-01");
        String collision = validDatasetJson(10).replace("candidate-01", "query-01");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> FashionClipSimilarityEvaluationMain.readDataset(writeDataset(duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("query and candidate imageId values must be unique");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> FashionClipSimilarityEvaluationMain.readDataset(writeDataset(collision)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("query and candidate imageId values must be unique");
    }

    @Test
    void rejectsUnknownRelationAndUnsupportedFields() throws Exception {
        String unknownRelation = validDatasetJson(10).replace("NEAR_DUPLICATE", "MAYBE_SIMILAR");
        String unsupported = validDatasetJson(10).replace("\"candidates\": [", "\"unexpected\": true,\n  \"candidates\": [");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> FashionClipSimilarityEvaluationMain.readDataset(writeDataset(unknownRelation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("relationLabel must be a supported relation");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> FashionClipSimilarityEvaluationMain.readDataset(writeDataset(unsupported)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset contains unsupported fields");
    }

    private static Path writeDataset(String json) throws Exception {
        Path path = Files.createTempFile("fashion-clip-dataset-", ".json");
        Files.writeString(path, json);
        return path;
    }

    private static String validDatasetJson(int count) {
        List<String> candidates = new java.util.ArrayList<>();
        String[] labels = {"NEAR_DUPLICATE", "VISUALLY_SIMILAR", "SAME_CATEGORY_DIFFERENT_DESIGN"};
        for (int index = 1; index <= count; index++) {
            String label = index <= labels.length ? labels[index - 1] : "UNRELATED";
            candidates.add("    {\"imageId\": \"candidate-%02d\", \"imagePath\": \"images/candidate-%02d.jpg\", \"relationLabel\": \"%s\"}"
                    .formatted(index, index, label));
        }
        return "{\n"
                + "  \"query\": {\"imageId\": \"query-01\", \"imagePath\": \"images/query.jpg\"},\n"
                + "  \"candidates\": [\n"
                + String.join(",\n", candidates)
                + "\n  ]\n"
                + "}\n";
    }
}
