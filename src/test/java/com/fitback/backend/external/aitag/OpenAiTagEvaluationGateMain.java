package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.TagType;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiTagEvaluationGateMain {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int REPORT_SCHEMA_VERSION = 2;
    private static final int CATALOG_IDENTITY_VERSION = 1;
    private static final int REQUIRED_CASES = 5;
    private static final int TOTAL_FALSE_POSITIVE_LIMIT = 15;
    private static final int MATERIAL_FALSE_POSITIVE_LIMIT = 1;
    private static final int PRECISION_TYPES_FALSE_POSITIVE_LIMIT = 10;
    private static final double MICRO_PRECISION_LIMIT = 0.3000d;
    private static final double MICRO_RECALL_LIMIT = 0.5294d;
    private static final int TOTAL_FALSE_NEGATIVE_LIMIT = 8;
    private static final double MICRO_F1_STRICT_LIMIT = 0.4000d;
    private static final Set<String> TAG_FIELDS = Set.of("type", "name");
    private static final Map<TagType, Integer> PRODUCTION_TAG_COUNTS = Map.of(
            TagType.STYLE, 9,
            TagType.SILHOUETTE, 12,
            TagType.MATERIAL, 8,
            TagType.DETAIL, 10,
            TagType.COLOR, 8
    );
    private static final Comparator<TagKey> TAG_ORDER = Comparator
            .comparing((TagKey key) -> key.type().name()).thenComparing(TagKey::name);

    private OpenAiTagEvaluationGateMain() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(requiredPath("AI_TAG_EVALUATION_REPORT"),
                requiredPath("AI_TAG_EVALUATION_CATALOG"), System.out);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(Path reportPath, Path catalogPath, PrintStream out) {
        GateResult result = evaluate(reportPath, catalogPath);
        result.print(out);
        return result.passed() ? 0 : 1;
    }

    private static GateResult evaluate(Path reportPath, Path catalogPath) {
        try {
            CatalogIdentity expectedIdentity = catalogIdentity(catalogPath);
            if (!hasProductionCatalogShape(expectedIdentity.catalogKeys())) {
                return GateResult.failed(Rule.CATALOG_IDENTITY_MISMATCH);
            }
            JsonNode root = OBJECT_MAPPER.readTree(Files.readString(reportPath));
            if (!root.isObject()) {
                return GateResult.failed(Rule.REPORT_FORMAT_INVALID);
            }

            RuleSet rules = new RuleSet();
            if (!root.path("reportSchemaVersion").isInt()
                    || root.path("reportSchemaVersion").asInt() != REPORT_SCHEMA_VERSION) {
                rules.add(Rule.REPORT_SCHEMA_UNSUPPORTED);
                return GateResult.from(rules, MetricsSnapshot.empty());
            }
            if (!hasCompleteCatalogIdentity(root.path("catalog"))) {
                rules.add(Rule.CATALOG_IDENTITY_MISSING);
                return GateResult.from(rules, MetricsSnapshot.empty());
            }
            CatalogIdentity reportIdentity = new CatalogIdentity(
                    root.path("catalog").path("identityVersion").asInt(),
                    root.path("catalog").path("tagCount").asInt(),
                    root.path("catalog").path("sha256").asText());
            if (!expectedIdentity.equals(reportIdentity)) {
                rules.add(Rule.CATALOG_IDENTITY_MISMATCH);
            }

            if (!root.path("cases").isArray()) {
                rules.add(Rule.REPORT_FORMAT_INVALID);
                return GateResult.from(rules, MetricsSnapshot.empty());
            }

            CaseMetrics metrics = readCaseMetrics(root.path("cases"), expectedIdentity.catalogKeys(), rules);
            applyMetricRules(metrics, rules);
            return GateResult.from(rules, metrics.snapshot());
        } catch (Exception exception) {
            return GateResult.failed(Rule.REPORT_FORMAT_INVALID);
        }
    }

    private static boolean hasCompleteCatalogIdentity(JsonNode catalog) {
        return catalog.isObject()
                && catalog.path("identityVersion").isInt()
                && catalog.path("tagCount").isInt()
                && catalog.path("sha256").isTextual()
                && catalog.path("sha256").asText().matches("[0-9a-f]{64}");
    }

    private static CaseMetrics readCaseMetrics(JsonNode cases, Set<TagKey> catalogKeys, RuleSet rules) {
        CaseMetrics metrics = new CaseMetrics();
        Set<String> caseIds = new LinkedHashSet<>();
        for (JsonNode item : cases) {
            CaseRecord record = readCase(item);
            metrics.totalCases++;
            if (!caseIds.add(record.imageId())) {
                rules.add(Rule.CASE_ID_DUPLICATE);
            }
            if (!"SUCCESS".equals(record.finalStatus()) || !record.isFinalSuccess()) {
                rules.add(Rule.FINAL_CASE_FAILURE);
            } else {
                metrics.successfulCases++;
            }
            if (record.hasFinalProviderOrParserFailure()) {
                rules.add(Rule.FINAL_PROVIDER_OR_PARSER_FAILURE);
            }
            if (!catalogKeys.containsAll(record.expected()) || !catalogKeys.containsAll(record.predicted())) {
                rules.add(Rule.UNKNOWN_CANONICAL_TAG);
            }

            Set<TagKey> truePositives = new LinkedHashSet<>(record.expected());
            truePositives.retainAll(record.predicted());
            Set<TagKey> falsePositives = new LinkedHashSet<>(record.predicted());
            falsePositives.removeAll(record.expected());
            Set<TagKey> falseNegatives = new LinkedHashSet<>(record.expected());
            falseNegatives.removeAll(record.predicted());

            metrics.truePositiveCount += truePositives.size();
            metrics.falsePositiveCount += falsePositives.size();
            metrics.falseNegativeCount += falseNegatives.size();
            for (TagKey falsePositive : falsePositives) {
                metrics.falsePositiveTypeCounts.merge(falsePositive.type(), 1, Integer::sum);
            }
            if ("public-bottom-01".equals(record.imageId()) && truePositives.contains(new TagKey(TagType.DETAIL, "포켓"))) {
                metrics.publicBottomPocketTruePositive = true;
            }
            if ("ai-bottom-02".equals(record.imageId()) && truePositives.contains(new TagKey(TagType.DETAIL, "턱"))) {
                metrics.aiBottomTuckTruePositive = true;
            }
            if ("outer-01".equals(record.imageId()) && !truePositives.isEmpty()) {
                metrics.outerTruePositive = true;
            }
        }
        if (metrics.totalCases != REQUIRED_CASES || metrics.successfulCases != REQUIRED_CASES) {
            rules.add(Rule.CASE_COUNT_INVALID);
        }
        return metrics;
    }

    private static CaseRecord readCase(JsonNode item) {
        if (!item.isObject()) {
            throw new IllegalArgumentException("case must be an object");
        }
        return new CaseRecord(
                requiredText(item, "imageId"),
                readTags(item.path("expectedCanonicalTags"), true),
                readTags(item.path("predictedCanonicalTags"), false),
                nullableText(item.path("error")),
                nullableText(item.path("providerErrorCategory")),
                nullableText(item.path("responseParsingCategory")),
                nullableText(item.path("finalStatus"))
        );
    }

    private static Set<TagKey> readTags(JsonNode tags, boolean requireNonEmpty) {
        if (!tags.isArray() || (requireNonEmpty && tags.isEmpty())) {
            throw new IllegalArgumentException("tags must be an array");
        }
        Set<TagKey> result = new LinkedHashSet<>();
        for (JsonNode tag : tags) {
            if (!tag.isObject() || !TAG_FIELDS.equals(tag.propertyNames())) {
                throw new IllegalArgumentException("tag must be an object");
            }
            if (!result.add(new TagKey(
                    TagType.valueOf(requiredText(tag, "type")), requiredText(tag, "name")))) {
                throw new IllegalArgumentException("tags must be unique");
            }
        }
        return result;
    }

    private static void applyMetricRules(CaseMetrics metrics, RuleSet rules) {
        int materialFalsePositives = metrics.falsePositiveTypeCounts.getOrDefault(TagType.MATERIAL, 0);
        int precisionTypesFalsePositives = materialFalsePositives
                + metrics.falsePositiveTypeCounts.getOrDefault(TagType.DETAIL, 0)
                + metrics.falsePositiveTypeCounts.getOrDefault(TagType.SILHOUETTE, 0);

        if (metrics.falsePositiveCount > TOTAL_FALSE_POSITIVE_LIMIT) {
            rules.add(Rule.TOTAL_FALSE_POSITIVE_LIMIT);
        }
        if (materialFalsePositives > MATERIAL_FALSE_POSITIVE_LIMIT) {
            rules.add(Rule.MATERIAL_FALSE_POSITIVE_LIMIT);
        }
        if (precisionTypesFalsePositives > PRECISION_TYPES_FALSE_POSITIVE_LIMIT) {
            rules.add(Rule.PRECISION_TYPES_FALSE_POSITIVE_LIMIT);
        }
        if (metrics.precision() < MICRO_PRECISION_LIMIT) {
            rules.add(Rule.MICRO_PRECISION_LIMIT);
        }
        if (metrics.recall() < MICRO_RECALL_LIMIT) {
            rules.add(Rule.MICRO_RECALL_LIMIT);
        }
        if (metrics.falseNegativeCount > TOTAL_FALSE_NEGATIVE_LIMIT) {
            rules.add(Rule.TOTAL_FALSE_NEGATIVE_LIMIT);
        }
        if (metrics.f1() <= MICRO_F1_STRICT_LIMIT) {
            rules.add(Rule.MICRO_F1_STRICT_LIMIT);
        }
        if (!metrics.publicBottomPocketTruePositive) {
            rules.add(Rule.PUBLIC_BOTTOM_POCKET_TRUE_POSITIVE);
        }
        if (!metrics.aiBottomTuckTruePositive) {
            rules.add(Rule.AI_BOTTOM_TUCK_TRUE_POSITIVE);
        }
        if (!metrics.outerTruePositive) {
            rules.add(Rule.OUTER_TRUE_POSITIVE);
        }
    }

    private static CatalogIdentity catalogIdentity(Path catalogPath) throws Exception {
        byte[] bytes = Files.readAllBytes(catalogPath);
        JsonNode root = OBJECT_MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("catalog must be a non-empty array");
        }
        Set<TagKey> keys = readTags(root, true);
        return new CatalogIdentity(CATALOG_IDENTITY_VERSION, keys.size(), sha256(bytes), Set.copyOf(keys));
    }

    private static boolean hasProductionCatalogShape(Set<TagKey> catalogKeys) {
        if (catalogKeys.size() != 47) {
            return false;
        }
        Map<TagType, Integer> actualCounts = new LinkedHashMap<>();
        for (TagKey key : catalogKeys) {
            actualCounts.merge(key.type(), 1, Integer::sum);
        }
        return PRODUCTION_TAG_COUNTS.equals(actualCounts);
    }

    private static Path requiredPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        Path path = Path.of(value.trim());
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(name + " must reference a file");
        }
        return path;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode valueNode = node.path(field);
        if (!valueNode.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        String value = valueNode.asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        if (isWhitespace(first) || isWhitespace(last)) {
            throw new IllegalArgumentException(field + " must not have leading or trailing whitespace");
        }
        return value;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static String nullableText(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("nullable field must be text or null");
        }
        return node.asText();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0L ? 0.0d : round((double) numerator / denominator);
    }

    private static double f1(long truePositives, long falsePositives, long falseNegatives) {
        double precision = rate(truePositives, truePositives + falsePositives);
        double recall = rate(truePositives, truePositives + falseNegatives);
        return precision + recall == 0.0d ? 0.0d : round(2.0d * precision * recall / (precision + recall));
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    enum Rule {
        REPORT_FORMAT_INVALID,
        REPORT_SCHEMA_UNSUPPORTED,
        CATALOG_IDENTITY_MISSING,
        CATALOG_IDENTITY_MISMATCH,
        CASE_COUNT_INVALID,
        CASE_ID_DUPLICATE,
        FINAL_CASE_FAILURE,
        FINAL_PROVIDER_OR_PARSER_FAILURE,
        UNKNOWN_CANONICAL_TAG,
        TOTAL_FALSE_POSITIVE_LIMIT,
        MATERIAL_FALSE_POSITIVE_LIMIT,
        PRECISION_TYPES_FALSE_POSITIVE_LIMIT,
        MICRO_PRECISION_LIMIT,
        MICRO_RECALL_LIMIT,
        TOTAL_FALSE_NEGATIVE_LIMIT,
        MICRO_F1_STRICT_LIMIT,
        PUBLIC_BOTTOM_POCKET_TRUE_POSITIVE,
        AI_BOTTOM_TUCK_TRUE_POSITIVE,
        OUTER_TRUE_POSITIVE
    }

    private static final class RuleSet {
        private final Set<Rule> rules = new LinkedHashSet<>();

        void add(Rule rule) {
            rules.add(rule);
        }

        List<Rule> ordered() {
            List<Rule> ordered = new ArrayList<>();
            for (Rule rule : Rule.values()) {
                if (rules.contains(rule)) {
                    ordered.add(rule);
                }
            }
            return ordered;
        }
    }

    private record CatalogIdentity(int identityVersion, int tagCount, String sha256, Set<TagKey> catalogKeys) {
        private CatalogIdentity(int identityVersion, int tagCount, String sha256) {
            this(identityVersion, tagCount, sha256, Set.of());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CatalogIdentity identity)) {
                return false;
            }
            return identityVersion == identity.identityVersion
                    && tagCount == identity.tagCount
                    && sha256.equals(identity.sha256);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(identityVersion, tagCount, sha256);
        }
    }

    record TagKey(TagType type, String name) implements Comparable<TagKey> {
        @Override
        public int compareTo(TagKey other) {
            return TAG_ORDER.compare(this, other);
        }
    }

    private record CaseRecord(
            String imageId,
            Set<TagKey> expected,
            Set<TagKey> predicted,
            String error,
            String providerErrorCategory,
            String responseParsingCategory,
            String finalStatus
    ) {
        boolean isFinalSuccess() {
            return error == null && providerErrorCategory == null && responseParsingCategory == null;
        }

        boolean hasFinalProviderOrParserFailure() {
            return providerErrorCategory != null || responseParsingCategory != null;
        }
    }

    private static final class CaseMetrics {
        private int totalCases;
        private int successfulCases;
        private int truePositiveCount;
        private int falsePositiveCount;
        private int falseNegativeCount;
        private final Map<TagType, Integer> falsePositiveTypeCounts = new LinkedHashMap<>();
        private boolean publicBottomPocketTruePositive;
        private boolean aiBottomTuckTruePositive;
        private boolean outerTruePositive;

        double precision() {
            return rate(truePositiveCount, truePositiveCount + falsePositiveCount);
        }

        double recall() {
            return rate(truePositiveCount, truePositiveCount + falseNegativeCount);
        }

        double f1() {
            return OpenAiTagEvaluationGateMain.f1(truePositiveCount, falsePositiveCount, falseNegativeCount);
        }

        MetricsSnapshot snapshot() {
            return new MetricsSnapshot(
                    totalCases, successfulCases, truePositiveCount, falsePositiveCount,
                    falseNegativeCount, precision(), recall(), f1());
        }
    }

    private record MetricsSnapshot(
            int totalCases,
            int successfulCases,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1
    ) {
        static MetricsSnapshot empty() {
            return new MetricsSnapshot(0, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);
        }
    }

    private record GateResult(List<Rule> failures, MetricsSnapshot metrics) {
        static GateResult failed(Rule rule) {
            RuleSet rules = new RuleSet();
            rules.add(rule);
            return from(rules, MetricsSnapshot.empty());
        }

        static GateResult from(RuleSet rules, MetricsSnapshot metrics) {
            return new GateResult(rules.ordered(), metrics);
        }

        boolean passed() {
            return failures.isEmpty();
        }

        void print(PrintStream out) {
            out.printf(
                    "openAiTagEvaluationGate status=%s totalCases=%d successfulCases=%d tp=%d fp=%d fn=%d precision=%.4f recall=%.4f f1=%.4f%n",
                    passed() ? "PASS" : "FAIL", metrics.totalCases(), metrics.successfulCases(),
                    metrics.truePositives(), metrics.falsePositives(), metrics.falseNegatives(),
                    metrics.precision(), metrics.recall(), metrics.f1());
            for (Rule failure : failures) {
                out.println(failure.name());
            }
        }
    }
}
