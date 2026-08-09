package com.fitback.backend.external.fashionclip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Provider-free dataset and report skeleton for Fashion-CLIP PoC A. */
public final class FashionClipSimilarityEvaluationMain {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> DATASET_FIELDS = Set.of("query", "candidates");
    private static final Set<String> IMAGE_FIELDS = Set.of("imageId", "imagePath");
    private static final Set<String> CANDIDATE_FIELDS = Set.of("imageId", "imagePath", "relationLabel");
    private static final int MIN_CANDIDATES = 10;
    private static final int MAX_CANDIDATES = 20;

    private FashionClipSimilarityEvaluationMain() {
    }

    public static void main(String[] args) throws Exception {
        Path datasetPath = requiredPath("FASHION_CLIP_EVALUATION_DATASET").toAbsolutePath();
        Path outputDirectory = Path.of(env(
                "FASHION_CLIP_EVALUATION_OUTPUT_DIR", "build/fashion-clip-evaluation"));
        writeReport(outputDirectory.resolve("fashion-clip-similarity-evaluation.json"), readDataset(datasetPath));
    }

    static Dataset readDataset(Path path) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(path));
        requireOnlyFields(root, DATASET_FIELDS, "dataset");
        JsonNode queryNode = root.path("query");
        requireOnlyFields(queryNode, IMAGE_FIELDS, "query");
        QueryImage query = new QueryImage(requiredText(queryNode, "imageId"), requiredText(queryNode, "imagePath"));

        JsonNode candidatesNode = root.path("candidates");
        if (!candidatesNode.isArray()
                || candidatesNode.size() < MIN_CANDIDATES
                || candidatesNode.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("candidates must contain 10 to 20 items");
        }

        Set<String> imageIds = new LinkedHashSet<>();
        imageIds.add(query.imageId());
        List<CandidateImage> candidates = new ArrayList<>();
        for (JsonNode item : candidatesNode) {
            requireOnlyFields(item, CANDIDATE_FIELDS, "candidate");
            CandidateImage candidate = new CandidateImage(
                    requiredText(item, "imageId"),
                    requiredText(item, "imagePath"),
                    parseRelationLabel(item));
            if (!imageIds.add(candidate.imageId())) {
                throw new IllegalArgumentException("query and candidate imageId values must be unique");
            }
            candidates.add(candidate);
        }
        return new Dataset(query, List.copyOf(candidates));
    }

    static void writeReport(Path outputPath, Dataset dataset) throws IOException {
        writeReport(outputPath, dataset, null, null);
    }

    static void writeReport(
            Path outputPath,
            Dataset dataset,
            FashionClipEmbeddingProvider embeddingProvider,
            Path datasetDirectory
    ) throws IOException {
        List<double[]> embeddings = null;
        if (embeddingProvider != null) {
            if (datasetDirectory == null) {
                throw new IllegalArgumentException("datasetDirectory must not be null");
            }
            List<FashionClipImageInput> inputs = new ArrayList<>();
            inputs.add(readImageInput(datasetDirectory, dataset.query().imagePath()));
            for (CandidateImage candidate : dataset.candidates()) {
                inputs.add(readImageInput(datasetDirectory, candidate.imagePath()));
            }
            FashionClipEmbeddingProvider.validateBatchInputs(inputs);
            embeddings = FashionClipEmbeddingProvider.validateBatchResult(
                    inputs.size(), embeddingProvider.embedBatch(inputs));
        }
        List<double[]> finalEmbeddings = embeddings;
        List<PairResult> pairs = new ArrayList<>();
        for (int index = 0; index < dataset.candidates().size(); index++) {
            CandidateImage candidate = dataset.candidates().get(index);
            Double similarity = finalEmbeddings == null
                    ? null
                    : FashionClipSimilarity.cosineSimilarity(
                            finalEmbeddings.get(0), finalEmbeddings.get(index + 1));
            pairs.add(new PairResult(
                    dataset.query().imageId(), candidate.imageId(), candidate.imagePath(),
                    candidate.relationLabel(), similarity));
        }
        Report report = new Report(Instant.now().toString(), dataset.query(), pairs);
        Files.createDirectories(outputPath.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), report);
    }

    private static FashionClipImageInput readImageInput(Path datasetDirectory, String imagePath)
            throws IOException {
        return new FashionClipImageInput(
                Files.readAllBytes(resolveImage(datasetDirectory, imagePath)), contentType(imagePath));
    }

    private static Path resolveImage(Path datasetDirectory, String imagePath) throws IOException {
        Path root = datasetDirectory.toAbsolutePath().normalize();
        Path resolved = root.resolve(imagePath).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved) || contentType(imagePath) == null) {
            throw new IllegalArgumentException("imagePath must reference a JPEG, PNG, or WEBP file");
        }
        return resolved;
    }

    private static String contentType(String imagePath) {
        String name = Path.of(imagePath).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        throw new IllegalArgumentException("imagePath must reference a JPEG, PNG, or WEBP file");
    }

    private static RelationLabel parseRelationLabel(JsonNode node) {
        String value = requiredText(node, "relationLabel");
        try {
            return RelationLabel.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("relationLabel must be a supported relation", exception);
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowedFields, String objectName) {
        if (!node.isObject() || !allowedFields.containsAll(node.propertyNames())) {
            throw new IllegalArgumentException(objectName + " contains unsupported fields");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (field.endsWith("Path") && Path.of(value).isAbsolute()) {
            throw new IllegalArgumentException(field + " must be relative");
        }
        return value.trim();
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredEnv(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(name + " must reference a file");
        }
        return path;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    enum RelationLabel {
        NEAR_DUPLICATE,
        VISUALLY_SIMILAR,
        SAME_CATEGORY_DIFFERENT_DESIGN,
        UNRELATED
    }

    record Dataset(QueryImage query, List<CandidateImage> candidates) {
        Dataset {
            candidates = List.copyOf(candidates);
        }
    }

    record QueryImage(String imageId, String imagePath) {
    }

    record CandidateImage(String imageId, String imagePath, RelationLabel relationLabel) {
    }

    record PairResult(
            String queryImageId,
            String candidateImageId,
            String candidateImagePath,
            RelationLabel relationLabel,
            Double cosineSimilarity
    ) {
    }

    record Report(String generatedAt, QueryImage query, List<PairResult> pairs) {
    }
}
