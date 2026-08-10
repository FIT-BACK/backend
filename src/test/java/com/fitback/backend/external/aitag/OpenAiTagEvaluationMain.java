package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.external.aitag.config.AiTagProperties;
import com.fitback.backend.external.aitag.openai.OpenAiTagModelClient;
import com.fitback.backend.global.exception.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiTagEvaluationMain {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> DATASET_FIELDS = Set.of("cases");
    private static final Set<String> CASE_FIELDS = Set.of(
            "imageId", "imagePath", "expectedCanonicalTags"
    );
    private static final Set<String> TAG_FIELDS = Set.of("type", "name");
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<Integer> RETRYABLE_PROVIDER_STATUSES = Set.of(500, 502, 503, 504);
    private static final String UNAVAILABLE_X_REQUEST_ID = "UNAVAILABLE";
    private static final Sleeper SYSTEM_SLEEPER = Thread::sleep;
    private static final Jitter SYSTEM_JITTER = bound -> ThreadLocalRandom.current().nextLong(bound);
    private static final Comparator<TagKey> TAG_ORDER = Comparator
            .comparing((TagKey key) -> key.type().name()).thenComparing(TagKey::name);

    private OpenAiTagEvaluationMain() {
    }

    public static void main(String[] args) throws Exception {
        Path datasetPath = requiredPath("AI_TAG_EVALUATION_DATASET").toAbsolutePath();
        Path catalogPath = requiredPath("AI_TAG_EVALUATION_CATALOG").toAbsolutePath();
        Path outputDirectory = Path.of(env("AI_TAG_EVALUATION_OUTPUT_DIR", "build/openai-tag-evaluation"));
        List<Tag> catalog = readCatalog(catalogPath);
        Set<TagKey> catalogKeys = catalogKeys(catalog);
        List<EvaluationCase> cases = readDataset(datasetPath);
        validateExpectedTags(cases, catalogKeys);
        validateImagePaths(datasetPath.getParent(), cases);

        AiTagProperties.OpenAi openAi = new AiTagProperties.OpenAi(
                requiredEnv("FITBACK_AI_OPENAI_API_KEY"), requiredEnv("FITBACK_AI_OPENAI_MODEL"));
        OpenAiTagModelClient client = new OpenAiTagModelClient(
                openAi, Duration.parse(env("FITBACK_AI_REQUEST_TIMEOUT", "PT30S")), OBJECT_MAPPER);
        AiTagModelRequest request = new AiTagRequestFactory().create(catalog);

        List<CaseResult> results = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            results.add(evaluate(client, request, datasetPath.getParent(), evaluationCase, catalogKeys));
        }
        Files.createDirectories(outputDirectory);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                outputDirectory.resolve("openai-tag-evaluation.json").toFile(),
                new EvaluationReport(Instant.now().toString(), openAi.model(), summarize(results), results));
    }

    static CaseResult successfulCase(
            EvaluationCase evaluationCase, AiTagModelResult result, Set<TagKey> catalogKeys
    ) {
        return successfulCase(evaluationCase, result, catalogKeys, null);
    }

    static CaseResult successfulCase(
            EvaluationCase evaluationCase,
            AiTagModelResult result,
            Set<TagKey> catalogKeys,
            Long base64ImageLength
    ) {
        return successfulCase(
                evaluationCase, result, catalogKeys, base64ImageLength, 1,
                List.of(AttemptMetadata.success(1, result))
        );
    }

    static CaseResult successfulCase(
            EvaluationCase evaluationCase,
            AiTagModelResult result,
            Set<TagKey> catalogKeys,
            Long base64ImageLength,
            int attemptCount,
            List<AttemptMetadata> attempts
    ) {
        Set<TagKey> expected = tagKeys(evaluationCase.expectedCanonicalTags());
        Set<TagKey> predicted = tagKeys(result.canonicalTags());
        return new CaseResult(
                evaluationCase.imageId(), evaluationCase.imagePath(), sorted(expected), sorted(predicted),
                sorted(difference(expected, predicted)), sorted(difference(predicted, expected)),
                sorted(difference(predicted, catalogKeys)), result.inputTokens(), result.outputTokens(),
                result.elapsedMillis(), null, null, null, base64ImageLength, null,
                attemptCount, "SUCCESS", attempts);
    }

    static EvaluationSummary summarize(List<CaseResult> results) {
        List<CaseResult> successful = results.stream().filter(result -> result.error() == null).toList();
        long truePositives = results.stream().mapToLong(OpenAiTagEvaluationMain::truePositives).sum();
        long falsePositives = results.stream().mapToLong(result -> result.falsePositives().size()).sum();
        long falseNegatives = results.stream().mapToLong(result -> result.falseNegatives().size()).sum();
        long exactMatches = results.stream().filter(OpenAiTagEvaluationMain::isExactMatch).count();
        return new EvaluationSummary(
                results.size(), successful.size(), results.size() - successful.size(),
                new Metrics(precision(truePositives, falsePositives), recall(truePositives, falseNegatives),
                        f1(truePositives, falsePositives, falseNegatives)),
                new Metrics(
                        average(results.stream().mapToDouble(result -> precision(
                                truePositives(result), result.falsePositives().size()))),
                        average(results.stream().mapToDouble(result -> recall(
                                truePositives(result), result.falseNegatives().size()))),
                        average(results.stream().mapToDouble(OpenAiTagEvaluationMain::f1))),
                exactMatches, rate(exactMatches, results.size()),
                failureTags(results, CaseResult::falseNegatives),
                failureTags(results, CaseResult::falsePositives),
                failureTags(results, CaseResult::unknownCanonicalTags),
                latency(successful), tokens(successful));
    }

    private static CaseResult evaluate(
            OpenAiTagModelClient client, AiTagModelRequest request, Path datasetDirectory,
            EvaluationCase evaluationCase, Set<TagKey> catalogKeys
    ) {
        Long base64ImageLength = null;
        try {
            Path imagePath = resolveImage(datasetDirectory, evaluationCase.imagePath());
            byte[] imageBytes = Files.readAllBytes(imagePath);
            base64ImageLength = base64ImageLength(imageBytes.length);
            RetryResult retryResult = analyzeWithRetry(
                    () -> invoke(client, new AiTagImage(imageBytes, contentType(imagePath)), request),
                    SYSTEM_SLEEPER,
                    SYSTEM_JITTER
            );
            if (retryResult.successful()) {
                return successfulCase(
                        evaluationCase, retryResult.result(), catalogKeys, base64ImageLength,
                        retryResult.attemptCount(), retryResult.attempts()
                );
            }
            EvaluationFailure failure = retryResult.failure();
            return CaseResult.failed(
                    evaluationCase,
                    failure.error(), failure.elapsedMillis(), failure.providerHttpStatus(),
                    failure.providerErrorCategory(), failure.responseParsingCategory(),
                    base64ImageLength, retryResult.attemptCount(), retryResult.attempts()
            );
        } catch (RuntimeException exception) {
            return CaseResult.failed(evaluationCase, "UNEXPECTED_EVALUATION_FAILURE");
        } catch (Exception exception) {
            return CaseResult.failed(evaluationCase, "EVALUATION_INPUT_FAILURE");
        }
    }

    private static EvaluationAttempt invoke(
            OpenAiTagModelClient client, AiTagImage image, AiTagModelRequest request
    ) {
        try {
            return EvaluationAttempt.success(client.analyze(image, request));
        } catch (OpenAiTagModelClient.ProviderFailure exception) {
            return EvaluationAttempt.failure(new EvaluationFailure(
                    exception.getErrorCode().getCode(), exception.elapsedMillis(),
                    exception.providerHttpStatus(), exception.providerErrorCategory(),
                    exception.responseParsingCategory(), exception.xRequestId()
            ));
        } catch (BusinessException exception) {
            return EvaluationAttempt.failure(new EvaluationFailure(
                    exception.getErrorCode().getCode(), null, null, null, null
            ));
        } catch (RuntimeException exception) {
            return EvaluationAttempt.failure(new EvaluationFailure(
                    "UNEXPECTED_EVALUATION_FAILURE", null, null, null, null
            ));
        } catch (Exception exception) {
            return EvaluationAttempt.failure(new EvaluationFailure(
                    "EVALUATION_INPUT_FAILURE", null, null, null, null
            ));
        }
    }

    static RetryResult analyzeWithRetry(EvaluationCall call, Sleeper sleeper, Jitter jitter) {
        List<AttemptMetadata> attempts = new ArrayList<>();
        for (int attemptCount = 1; attemptCount <= MAX_ATTEMPTS; attemptCount++) {
            EvaluationAttempt attempt = call.invoke();
            attempts.add(AttemptMetadata.from(attemptCount, attempt));
            if (attempt.successful()) {
                return RetryResult.success(attempt.result(), attemptCount, attempts);
            }
            EvaluationFailure failure = attempt.failure();
            if (attemptCount == MAX_ATTEMPTS || !isRetryable(failure)) {
                return RetryResult.failure(failure, attemptCount, attempts);
            }
            long delayMillis = retryDelayMillis(attemptCount, jitter);
            try {
                sleeper.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return RetryResult.failure(
                        new EvaluationFailure("EVALUATION_RETRY_INTERRUPTED", null, null, null, null),
                        attemptCount, attempts
                );
            }
        }
        throw new IllegalStateException("retry loop exhausted without a result");
    }

    private static boolean isRetryable(EvaluationFailure failure) {
        return failure.providerHttpStatus() != null
                && RETRYABLE_PROVIDER_STATUSES.contains(failure.providerHttpStatus())
                && "SERVER_ERROR".equals(failure.providerErrorCategory())
                && failure.responseParsingCategory() == null;
    }

    static long retryDelayMillis(int failedAttemptCount, Jitter jitter) {
        long lowerBound = failedAttemptCount == 1 ? 250L : 500L;
        long upperBound = failedAttemptCount == 1 ? 500L : 1_000L;
        return lowerBound + jitter.nextLong(upperBound - lowerBound + 1L);
    }

    static long base64ImageLength(long imageByteLength) {
        if (imageByteLength < 0) {
            throw new IllegalArgumentException("imageByteLength must not be negative");
        }
        return 4L * ((imageByteLength + 2L) / 3L);
    }

    static Path resolveImage(Path datasetDirectory, String imagePath) throws IOException {
        Path candidate = Path.of(imagePath);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("imagePath must be relative to the dataset");
        }
        Path resolved = datasetDirectory.resolve(candidate).normalize();
        if (!resolved.startsWith(datasetDirectory) || !Files.isRegularFile(resolved)
                || contentType(resolved) == null) {
            throw new IllegalArgumentException("imagePath must reference a JPEG, PNG, or WEBP file");
        }
        Path datasetRoot = datasetDirectory.toRealPath();
        Path imageRealPath = resolved.toRealPath();
        if (!imageRealPath.startsWith(datasetRoot)) {
            throw new IllegalArgumentException("imagePath must remain within the dataset directory");
        }
        return imageRealPath;
    }

    private static void validateImagePaths(Path datasetDirectory, List<EvaluationCase> cases) throws IOException {
        for (EvaluationCase evaluationCase : cases) {
            resolveImage(datasetDirectory, evaluationCase.imagePath());
        }
    }

    static List<EvaluationCase> readDataset(Path path) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(path));
        requireOnlyFields(root, DATASET_FIELDS, "dataset");
        if (!root.path("cases").isArray() || root.path("cases").isEmpty()) {
            throw new IllegalArgumentException("dataset cases must not be empty");
        }
        List<EvaluationCase> cases = new ArrayList<>();
        Set<String> imageIds = new LinkedHashSet<>();
        for (JsonNode item : root.path("cases")) {
            requireOnlyFields(item, CASE_FIELDS, "dataset case");
            String imageId = requiredText(item, "imageId");
            if (!imageIds.add(imageId)) {
                throw new IllegalArgumentException("dataset imageId values must be unique");
            }
            JsonNode expectedTags = item.path("expectedCanonicalTags");
            if (!expectedTags.isArray() || expectedTags.isEmpty()) {
                throw new IllegalArgumentException("expectedCanonicalTags must not be empty");
            }
            List<AiTagPrediction> tags = new ArrayList<>();
            for (JsonNode tag : expectedTags) {
                requireOnlyFields(tag, TAG_FIELDS, "expected canonical tag");
                tags.add(new AiTagPrediction(
                        TagType.valueOf(requiredText(tag, "type")), requiredText(tag, "name")));
            }
            if (tagKeys(tags).size() != tags.size()) {
                throw new IllegalArgumentException("expectedCanonicalTags must be unique");
            }
            cases.add(new EvaluationCase(imageId, requiredText(item, "imagePath"), tags));
        }
        return List.copyOf(cases);
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowedFields, String objectName) {
        if (!node.isObject() || !allowedFields.containsAll(node.propertyNames())) {
            throw new IllegalArgumentException(objectName + " contains unsupported fields");
        }
    }

    private static List<Tag> readCatalog(Path path) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(path));
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("catalog must not be empty");
        }
        List<Tag> tags = new ArrayList<>();
        for (JsonNode item : root) {
            tags.add(Tag.create(requiredText(item, "name"), TagType.valueOf(requiredText(item, "type"))));
        }
        return List.copyOf(tags);
    }

    private static void validateExpectedTags(List<EvaluationCase> cases, Set<TagKey> catalogKeys) {
        if (cases.stream().flatMap(evaluationCase -> evaluationCase.expectedCanonicalTags().stream())
                .map(OpenAiTagEvaluationMain::tagKey).anyMatch(tag -> !catalogKeys.contains(tag))) {
            throw new IllegalArgumentException("expectedCanonicalTags must be present in the catalog");
        }
    }

    private static Set<TagKey> catalogKeys(List<Tag> catalog) {
        Set<TagKey> keys = new LinkedHashSet<>();
        for (Tag tag : catalog) {
            if (!keys.add(new TagKey(tag.getTagType(), tag.getTagName()))) {
                throw new IllegalArgumentException("catalog tags must be unique by type and name");
            }
        }
        return Set.copyOf(keys);
    }

    private static Set<TagKey> tagKeys(List<AiTagPrediction> tags) {
        Set<TagKey> result = new LinkedHashSet<>();
        for (AiTagPrediction tag : tags) {
            result.add(tagKey(tag));
        }
        return result;
    }

    private static TagKey tagKey(AiTagPrediction tag) {
        return new TagKey(tag.type(), tag.name());
    }

    private static Set<TagKey> difference(Set<TagKey> left, Set<TagKey> right) {
        Set<TagKey> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static List<TagKey> sorted(Set<TagKey> tags) {
        return tags.stream().sorted(TAG_ORDER).toList();
    }

    private static long truePositives(CaseResult result) {
        return result.predictedCanonicalTags().size() - result.falsePositives().size();
    }

    private static boolean isExactMatch(CaseResult result) {
        return result.falseNegatives().isEmpty() && result.falsePositives().isEmpty();
    }

    private static double f1(CaseResult result) {
        return f1(truePositives(result), result.falsePositives().size(), result.falseNegatives().size());
    }

    private static double precision(long truePositives, long falsePositives) {
        return rate(truePositives, truePositives + falsePositives);
    }

    private static double recall(long truePositives, long falseNegatives) {
        return rate(truePositives, truePositives + falseNegatives);
    }

    private static double f1(long truePositives, long falsePositives, long falseNegatives) {
        double precision = precision(truePositives, falsePositives);
        double recall = recall(truePositives, falseNegatives);
        return precision + recall == 0.0d ? 0.0d : round(2.0d * precision * recall / (precision + recall));
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : round((double) numerator / denominator);
    }

    private static double average(java.util.stream.DoubleStream values) {
        java.util.OptionalDouble average = values.average();
        return average.isEmpty() ? 0.0d : round(average.getAsDouble());
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static Latency latency(List<CaseResult> results) {
        if (results.isEmpty()) {
            return new Latency(null, null, null);
        }
        return new Latency(
                results.stream().mapToLong(CaseResult::elapsedMillis).min().orElseThrow(),
                results.stream().mapToLong(CaseResult::elapsedMillis).max().orElseThrow(),
                round(results.stream().mapToLong(CaseResult::elapsedMillis).average().orElseThrow()));
    }

    private static TokenUsage tokens(List<CaseResult> results) {
        return new TokenUsage(tokenTotal(results, true), tokenTotal(results, false));
    }

    private static FailureTags failureTags(
            List<CaseResult> results,
            java.util.function.Function<CaseResult, List<TagKey>> tagExtractor
    ) {
        List<TagKey> tags = results.stream().flatMap(result -> tagExtractor.apply(result).stream()).toList();
        java.util.Map<TagKey, Long> counts = new java.util.LinkedHashMap<>();
        for (TagKey tag : tags) {
            counts.merge(tag, 1L, Long::sum);
        }
        List<TagCount> byTag = counts.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey(TAG_ORDER))
                .map(entry -> new TagCount(entry.getKey(), entry.getValue()))
                .toList();
        return new FailureTags(tags.size(), byTag);
    }

    private static TokenTotal tokenTotal(List<CaseResult> results, boolean input) {
        List<Integer> reported = results.stream().map(result -> input ? result.inputTokens() : result.outputTokens())
                .filter(java.util.Objects::nonNull).toList();
        if (reported.isEmpty()) {
            return new TokenTotal(0, null, null);
        }
        long total = reported.stream().mapToLong(Integer::longValue).sum();
        return new TokenTotal(reported.size(), total, round((double) total / reported.size()));
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
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

    private static String contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    record EvaluationCase(String imageId, String imagePath, List<AiTagPrediction> expectedCanonicalTags) {
        EvaluationCase {
            expectedCanonicalTags = List.copyOf(expectedCanonicalTags);
        }
    }

    record TagKey(TagType type, String name) {
    }

    record CaseResult(
            String imageId, String imagePath, List<TagKey> expectedCanonicalTags,
            List<TagKey> predictedCanonicalTags, List<TagKey> falseNegatives,
            List<TagKey> falsePositives, List<TagKey> unknownCanonicalTags,
            Integer inputTokens, Integer outputTokens, Long elapsedMillis,
            Integer providerHttpStatus, String providerErrorCategory, String responseParsingCategory,
            Long base64ImageLength, String error, int attemptCount, String finalStatus,
            List<AttemptMetadata> attempts
    ) {
        static CaseResult failed(EvaluationCase evaluationCase, String error) {
            return failed(evaluationCase, error, null, null, null, null, null, 0, List.of());
        }

        static CaseResult failed(
                EvaluationCase evaluationCase,
                String error,
                Long elapsedMillis,
                Integer providerHttpStatus,
                String providerErrorCategory,
                String responseParsingCategory,
                Long base64ImageLength
        ) {
            return failed(evaluationCase, error, elapsedMillis, providerHttpStatus,
                    providerErrorCategory, responseParsingCategory, base64ImageLength, 1);
        }

        static CaseResult failed(
                EvaluationCase evaluationCase,
                String error,
                Long elapsedMillis,
                Integer providerHttpStatus,
                String providerErrorCategory,
                String responseParsingCategory,
                Long base64ImageLength,
                int attemptCount
        ) {
            return failed(
                    evaluationCase, error, elapsedMillis, providerHttpStatus, providerErrorCategory,
                    responseParsingCategory, base64ImageLength, attemptCount,
                    List.of(new AttemptMetadata(
                            attemptCount, providerHttpStatus, providerErrorCategory, elapsedMillis,
                            UNAVAILABLE_X_REQUEST_ID))
            );
        }

        static CaseResult failed(
                EvaluationCase evaluationCase,
                String error,
                Long elapsedMillis,
                Integer providerHttpStatus,
                String providerErrorCategory,
                String responseParsingCategory,
                Long base64ImageLength,
                int attemptCount,
                List<AttemptMetadata> attempts
        ) {
            return new CaseResult(evaluationCase.imageId(), evaluationCase.imagePath(),
                    sorted(tagKeys(evaluationCase.expectedCanonicalTags())), List.of(),
                    sorted(tagKeys(evaluationCase.expectedCanonicalTags())), List.of(), List.of(),
                    null, null, elapsedMillis, providerHttpStatus, providerErrorCategory,
                    responseParsingCategory, base64ImageLength, error, attemptCount, "FAILED", attempts);
        }
    }

    record AttemptMetadata(
            int attempt,
            Integer httpStatus,
            String providerErrorCategory,
            Long elapsedMillis,
            String xRequestId
    ) {
        static AttemptMetadata from(int attempt, EvaluationAttempt evaluationAttempt) {
            if (evaluationAttempt.successful()) {
                return success(attempt, evaluationAttempt.result());
            }
            return failure(attempt, evaluationAttempt.failure());
        }

        static AttemptMetadata success(int attempt, AiTagModelResult result) {
            return new AttemptMetadata(attempt, null, null, result.elapsedMillis(), result.xRequestId());
        }

        static AttemptMetadata failure(int attempt, EvaluationFailure failure) {
            return new AttemptMetadata(
                    attempt, failure.providerHttpStatus(), failure.providerErrorCategory(),
                    failure.elapsedMillis(), failure.xRequestId()
            );
        }

        AttemptMetadata {
            xRequestId = xRequestId == null ? UNAVAILABLE_X_REQUEST_ID : xRequestId;
        }
    }

    static CaseResult successfulCase(
            EvaluationCase evaluationCase,
            AiTagModelResult result,
            Set<TagKey> catalogKeys,
            Long base64ImageLength,
            int attemptCount
    ) {
        return successfulCase(
                evaluationCase, result, catalogKeys, base64ImageLength, attemptCount,
                List.of(AttemptMetadata.success(attemptCount, result))
        );
    }

    record EvaluationFailure(
            String error,
            Long elapsedMillis,
            Integer providerHttpStatus,
            String providerErrorCategory,
            String responseParsingCategory,
            String xRequestId
    ) {
        EvaluationFailure(
                String error,
                Long elapsedMillis,
                Integer providerHttpStatus,
                String providerErrorCategory,
                String responseParsingCategory
        ) {
            this(error, elapsedMillis, providerHttpStatus, providerErrorCategory,
                    responseParsingCategory, UNAVAILABLE_X_REQUEST_ID);
        }

        EvaluationFailure {
            xRequestId = xRequestId == null ? UNAVAILABLE_X_REQUEST_ID : xRequestId;
        }
    }

    record EvaluationAttempt(AiTagModelResult result, EvaluationFailure failure) {
        static EvaluationAttempt success(AiTagModelResult result) {
            return new EvaluationAttempt(result, null);
        }

        static EvaluationAttempt failure(EvaluationFailure failure) {
            return new EvaluationAttempt(null, failure);
        }

        boolean successful() {
            return result != null;
        }
    }

    record RetryResult(
            AiTagModelResult result, EvaluationFailure failure, int attemptCount,
            List<AttemptMetadata> attempts
    ) {
        static RetryResult success(
                AiTagModelResult result, int attemptCount, List<AttemptMetadata> attempts
        ) {
            return new RetryResult(result, null, attemptCount, List.copyOf(attempts));
        }

        static RetryResult failure(
                EvaluationFailure failure, int attemptCount, List<AttemptMetadata> attempts
        ) {
            return new RetryResult(null, failure, attemptCount, List.copyOf(attempts));
        }

        boolean successful() {
            return result != null;
        }
    }

    @FunctionalInterface
    interface EvaluationCall {
        EvaluationAttempt invoke();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface Jitter {
        long nextLong(long boundExclusive);
    }

    record EvaluationReport(String generatedAt, String model, EvaluationSummary summary, List<CaseResult> cases) {
    }

    record EvaluationSummary(
            int totalCases, int successfulCases, int failedCases, Metrics micro, Metrics macro,
            long exactMatchCases, double exactMatchRate,
            FailureTags falseNegatives, FailureTags falsePositives, FailureTags unknownCanonicalTags,
            Latency latency, TokenUsage tokens
    ) {
    }

    record Metrics(double precision, double recall, double f1) {
    }

    record Latency(Long minMillis, Long maxMillis, Double averageMillis) {
    }

    record TokenUsage(TokenTotal input, TokenTotal output) {
    }

    record TokenTotal(int reportedCases, Long total, Double average) {
    }

    record FailureTags(long count, List<TagCount> byTag) {
    }

    record TagCount(TagKey tag, long count) {
    }
}
