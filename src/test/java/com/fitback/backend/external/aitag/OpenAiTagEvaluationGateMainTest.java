package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.TagType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class OpenAiTagEvaluationGateMainTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void passesInclusiveThresholdBoundariesAndIgnoresTamperedSummary(@TempDir Path directory) throws Exception {
        Fixture fixture = writeFixture(directory, report -> report.summary = tamperedPerfectSummary());

        GateOutput output = runGate(fixture);

        assertThat(output.exitCode()).isZero();
        assertThat(output.text())
                .contains("status=PASS", "tp=9", "fp=15", "fn=8", "recall=0.5294")
                .doesNotContain("SHOULD_NOT_BE_TRUSTED", fixture.report().toString(), fixture.catalog().toString());
    }

    @Test
    void recoveredTransientAttemptWithFinalSuccessIsValidAndOutputIsSafe(@TempDir Path directory) throws Exception {
        Fixture fixture = writeFixture(directory, report -> {
            report.cases.getFirst().attempts = List.of(
                    attempt(1, 500, "SERVER_ERROR", "req-secret-1"),
                    attempt(2, null, null, "req-secret-2"));
        });

        GateOutput output = runGate(fixture);

        assertThat(output.exitCode()).isZero();
        assertThat(output.text())
                .contains("status=PASS")
                .doesNotContain("req-secret", "images/public-bottom-01.jpg", fixture.report().toString(),
                        fixture.catalog().toString(), "test-key", "data:image", "raw-json");
    }

    @Test
    void emitsMultipleFailureRuleIdsInFrozenOrder(@TempDir Path directory) throws Exception {
        Fixture fixture = writeFixture(directory, report -> {
            report.cases.getFirst().finalStatus = "FAILED";
            report.cases.getFirst().error = "ANALYSIS409_1";
            report.cases.get(1).providerErrorCategory = "SERVER_ERROR";
            report.cases.get(2).predictedCanonicalTags.add(tag(TagType.STYLE, "unknown-style"));
        });

        GateOutput output = runGate(fixture);

        assertThat(output.exitCode()).isEqualTo(1);
        assertThat(output.text().lines().skip(1).toList()).containsExactly(
                "CASE_COUNT_INVALID",
                "FINAL_CASE_FAILURE",
                "FINAL_PROVIDER_OR_PARSER_FAILURE",
                "UNKNOWN_CANONICAL_TAG",
                "TOTAL_FALSE_POSITIVE_LIMIT");
    }

    @Test
    void rejectsMalformedJsonAsReportFormatInvalid(@TempDir Path directory) throws Exception {
        Fixture fixture = writeFixture(directory, report -> { });
        Files.writeString(fixture.report(), "{not-json");

        assertFailure(fixture, "REPORT_FORMAT_INVALID");
    }

    @Test
    void rejectsLegacySchemaAsUnsupported(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.reportSchemaVersion = 1),
                "REPORT_SCHEMA_UNSUPPORTED");
    }

    @Test
    void rejectsMissingCatalogIdentity(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.catalog = null),
                "CATALOG_IDENTITY_MISSING");
    }

    @Test
    void rejectsMismatchedCatalogIdentity(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.catalog.put("sha256", "0".repeat(64))),
                "CATALOG_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsUnsupportedCatalogIdentityVersionAsMismatch(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.catalog.put("identityVersion", 2)),
                "CATALOG_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsHistoricalCatalogShapeEvenWhenReportIdentityMatches(@TempDir Path directory) throws Exception {
        Path catalog = directory.resolve("catalog.json");
        List<Map<String, String>> historical = new ArrayList<>(catalogEntries());
        for (int removed = 0; removed < 4; removed++) {
            int index = java.util.stream.IntStream.range(0, historical.size())
                    .filter(candidate -> historical.get(candidate).get("name").startsWith("filler-silhouette-"))
                    .findFirst()
                    .orElseThrow();
            historical.remove(index);
        }
        assertThat(historical).hasSize(43);
        Files.writeString(catalog, OBJECT_MAPPER.writeValueAsString(historical));
        ReportFixture report = passingReport(catalog);
        Path reportPath = directory.resolve("report.json");
        Files.writeString(reportPath, OBJECT_MAPPER.writeValueAsString(report));

        assertFailure(new Fixture(reportPath, catalog), "CATALOG_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsInvalidCaseCount(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.cases = report.cases.subList(0, 4)),
                "CASE_COUNT_INVALID");
    }

    @Test
    void rejectsDuplicateCaseIds(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.cases.get(1).imageId = "public-bottom-01"),
                "CASE_ID_DUPLICATE");
    }

    @Test
    void rejectsDuplicatePredictedTagsAsMalformedReport(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.cases.getFirst().predictedCanonicalTags.add(
                new LinkedHashMap<>(report.cases.getFirst().predictedCanonicalTags.getFirst()))),
                "REPORT_FORMAT_INVALID");
    }

    @Test
    void rejectsEmptyExpectedTagsAsMalformedReport(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.cases.getFirst().expectedCanonicalTags.clear()),
                "REPORT_FORMAT_INVALID");
    }

    @Test
    void rejectsAsciiAndUnicodeBoundaryWhitespaceInsteadOfNormalizing(@TempDir Path directory) throws Exception {
        for (String name : List.of(
                " 포켓", "포켓 ",
                "\u2003포켓", "포켓\u2003",
                "\u00A0포켓", "포켓\u00A0")) {
            assertFailure(writeFixture(directory, report ->
                    report.cases.getFirst().expectedCanonicalTags.getFirst().put("name", name)),
                    "REPORT_FORMAT_INVALID");
        }
    }

    @Test
    void rejectsWhitespaceMutatedPredictedTagInsteadOfNormalizing(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().predictedCanonicalTags.getFirst().put("type", " DETAIL")),
                "REPORT_FORMAT_INVALID");
    }

    @Test
    void rejectsWhitespaceMutatedCatalogTagInsteadOfNormalizing(@TempDir Path directory) throws Exception {
        Fixture fixture = writeFixture(directory, report -> { });
        List<Map<String, String>> catalog = catalogEntries();
        catalog.getFirst().put("name", " " + catalog.getFirst().get("name"));
        Files.writeString(fixture.catalog(), OBJECT_MAPPER.writeValueAsString(catalog));

        assertFailure(fixture, "REPORT_FORMAT_INVALID");
    }

    @Test
    void rejectsFinalCaseFailure(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> {
            report.cases.getFirst().finalStatus = "FAILED";
            report.cases.getFirst().error = "ANALYSIS409_1";
        }), "FINAL_CASE_FAILURE");
    }

    @Test
    void rejectsFinalProviderOrParserFailure(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.cases.getFirst().providerErrorCategory = "SERVER_ERROR"),
                "FINAL_PROVIDER_OR_PARSER_FAILURE");
    }

    @Test
    void rejectsUnknownCanonicalTag(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.COLOR, "없는색"))),
                "UNKNOWN_CANONICAL_TAG");
    }

    @Test
    void rejectsTotalFalsePositiveAboveLimit(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.STYLE, "extra-style-01"))),
                "TOTAL_FALSE_POSITIVE_LIMIT");
    }

    @Test
    void rejectsMaterialFalsePositiveAboveLimit(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.MATERIAL, "extra-material-01"))),
                "MATERIAL_FALSE_POSITIVE_LIMIT");
    }

    @Test
    void rejectsPrecisionTypesFalsePositiveAboveLimit(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.DETAIL, "extra-detail-01"))),
                "PRECISION_TYPES_FALSE_POSITIVE_LIMIT");
    }

    @Test
    void rejectsMicroPrecisionBelowPointThirty(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> {
            report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.STYLE, "extra-style-01"));
            report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.MATERIAL, "extra-material-01"));
            report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.MATERIAL, "extra-material-02"));
            report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.DETAIL, "extra-detail-01"));
            report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.SILHOUETTE, "extra-silhouette-01"));
            report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.SILHOUETTE, "extra-silhouette-02"));
            report.cases.getFirst().predictedCanonicalTags.add(tag(TagType.COLOR, "extra-color-01"));
        }), "MICRO_PRECISION_LIMIT");
    }

    @Test
    void passesMicroPrecisionAtInclusivePointThirtyBoundary(@TempDir Path directory) throws Exception {
        Fixture fixture = writeFixture(directory, report -> report.cases = precisionBoundaryCases());

        GateOutput output = runGate(fixture);

        assertThat(output.exitCode()).isZero();
        assertThat(output.text()).contains("status=PASS", "precision=0.3000", "f1=0.4138");
    }

    @Test
    void rejectsMicroRecallBelowPointFiveTwoNineFour(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().expectedCanonicalTags.add(tag(TagType.STYLE, "extra-style-01"))),
                "MICRO_RECALL_LIMIT");
    }

    @Test
    void rejectsTotalFalseNegativeAboveLimit(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().expectedCanonicalTags.add(tag(TagType.STYLE, "extra-style-01"))),
                "TOTAL_FALSE_NEGATIVE_LIMIT");
    }

    @Test
    void rejectsMicroF1AtStrictPointFourBoundary(@TempDir Path directory) throws Exception {
        Fixture fixture = writeFixture(directory, report -> report.cases = strictF1BoundaryCases());

        GateOutput output = runGate(fixture);

        assertThat(output.text()).contains("f1=0.4000", "MICRO_F1_STRICT_LIMIT");
        assertThat(output.exitCode()).isEqualTo(1);
    }

    @Test
    void rejectsMissingPublicBottomPocketTruePositive(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.getFirst().predictedCanonicalTags.remove(tag(TagType.DETAIL, "포켓"))),
                "PUBLIC_BOTTOM_POCKET_TRUE_POSITIVE");
    }

    @Test
    void rejectsMissingAiBottomTuckTruePositive(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report ->
                report.cases.get(1).predictedCanonicalTags.remove(tag(TagType.DETAIL, "턱"))),
                "AI_BOTTOM_TUCK_TRUE_POSITIVE");
    }

    @Test
    void rejectsOuterCaseWithoutAnyTruePositive(@TempDir Path directory) throws Exception {
        assertFailure(writeFixture(directory, report -> report.cases.get(2).predictedCanonicalTags.clear()),
                "OUTER_TRUE_POSITIVE");
    }

    private static void assertFailure(Fixture fixture, String ruleId) throws Exception {
        GateOutput output = runGate(fixture);

        assertThat(output.exitCode()).isEqualTo(1);
        assertThat(output.text()).contains(ruleId);
        assertThat(output.text())
                .doesNotContain(fixture.report().toString(), fixture.catalog().toString(),
                        "images/", "req-secret", "test-key", "data:image", "raw-json");
    }

    private static Fixture writeFixture(Path directory, Consumer<ReportFixture> customizer) throws Exception {
        Path catalog = directory.resolve("catalog.json");
        Files.writeString(catalog, catalogJson());
        ReportFixture report = passingReport(catalog);
        customizer.accept(report);
        Path reportPath = directory.resolve("report.json");
        Files.writeString(reportPath, OBJECT_MAPPER.writeValueAsString(report));
        return new Fixture(reportPath, catalog);
    }

    private static GateOutput runGate(Fixture fixture) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exitCode = OpenAiTagEvaluationGateMain.run(
                fixture.report(), fixture.catalog(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new GateOutput(exitCode, bytes.toString(StandardCharsets.UTF_8));
    }

    private static ReportFixture passingReport(Path catalog) throws Exception {
        ReportFixture report = new ReportFixture();
        report.reportSchemaVersion = 2;
        report.catalog = new LinkedHashMap<>();
        report.catalog.put("identityVersion", 1);
        report.catalog.put("tagCount", OBJECT_MAPPER.readTree(Files.readString(catalog)).size());
        report.catalog.put("sha256", sha256(Files.readAllBytes(catalog)));
        report.generatedAt = "2026-08-11T00:00:00Z";
        report.model = "gpt-5.6-luna";
        report.summary = tamperedPerfectSummary();
        report.cases = passingCases();
        return report;
    }

    private static List<CaseFixture> passingCases() {
        return List.of(
                successCase("public-bottom-01",
                        tags(tag(TagType.DETAIL, "포켓"), tag(TagType.STYLE, "exp-style-01"),
                                tag(TagType.COLOR, "exp-color-01"), tag(TagType.MATERIAL, "exp-material-01")),
                        tags(tag(TagType.DETAIL, "포켓"), tag(TagType.STYLE, "exp-style-01"),
                                tag(TagType.MATERIAL, "fp-material-01"), tag(TagType.DETAIL, "fp-detail-01"),
                                tag(TagType.SILHOUETTE, "fp-silhouette-01"))),
                successCase("ai-bottom-02",
                        tags(tag(TagType.DETAIL, "턱"), tag(TagType.STYLE, "exp-style-02"),
                                tag(TagType.COLOR, "exp-color-02"), tag(TagType.MATERIAL, "exp-material-02")),
                        tags(tag(TagType.DETAIL, "턱"), tag(TagType.STYLE, "exp-style-02"),
                                tag(TagType.DETAIL, "fp-detail-02"), tag(TagType.SILHOUETTE, "fp-silhouette-02"),
                                tag(TagType.STYLE, "fp-style-01"))),
                successCase("outer-01",
                        tags(tag(TagType.STYLE, "exp-style-03"), tag(TagType.COLOR, "exp-color-03"),
                                tag(TagType.MATERIAL, "exp-material-03"), tag(TagType.DETAIL, "exp-detail-01")),
                        tags(tag(TagType.STYLE, "exp-style-03"), tag(TagType.COLOR, "exp-color-03"),
                                tag(TagType.DETAIL, "fp-detail-03"), tag(TagType.SILHOUETTE, "fp-silhouette-03"),
                                tag(TagType.STYLE, "fp-style-02"))),
                successCase("top-01",
                        tags(tag(TagType.STYLE, "exp-style-04"), tag(TagType.COLOR, "exp-color-04")),
                        tags(tag(TagType.STYLE, "exp-style-04"), tag(TagType.COLOR, "exp-color-04"),
                                tag(TagType.DETAIL, "fp-detail-04"), tag(TagType.SILHOUETTE, "fp-silhouette-04"),
                                tag(TagType.COLOR, "fp-color-01"))),
                successCase("dress-01",
                        tags(tag(TagType.STYLE, "exp-style-05"), tag(TagType.COLOR, "exp-color-05"),
                                tag(TagType.MATERIAL, "exp-material-04")),
                        tags(tag(TagType.STYLE, "exp-style-05"), tag(TagType.DETAIL, "fp-detail-05"),
                                tag(TagType.STYLE, "fp-style-03"), tag(TagType.COLOR, "fp-color-02")))
        );
    }

    private static List<CaseFixture> strictF1BoundaryCases() {
        return List.of(
                successCase("public-bottom-01",
                        tags(tag(TagType.DETAIL, "포켓"), tag(TagType.STYLE, "exp-style-01")),
                        tags(tag(TagType.DETAIL, "포켓"), tag(TagType.STYLE, "exp-style-01"),
                                tag(TagType.MATERIAL, "fp-material-01"), tag(TagType.DETAIL, "fp-detail-01"),
                                tag(TagType.SILHOUETTE, "fp-silhouette-01"))),
                successCase("ai-bottom-02",
                        tags(tag(TagType.DETAIL, "턱"), tag(TagType.STYLE, "exp-style-02")),
                        tags(tag(TagType.DETAIL, "턱"), tag(TagType.STYLE, "exp-style-02"),
                                tag(TagType.DETAIL, "fp-detail-02"), tag(TagType.SILHOUETTE, "fp-silhouette-02"),
                                tag(TagType.STYLE, "fp-style-01"))),
                successCase("outer-01",
                        tags(tag(TagType.STYLE, "exp-style-03"), tag(TagType.COLOR, "exp-color-03"),
                                tag(TagType.MATERIAL, "exp-material-03")),
                        tags(tag(TagType.STYLE, "exp-style-03"), tag(TagType.COLOR, "exp-color-03"),
                                tag(TagType.DETAIL, "fp-detail-03"), tag(TagType.SILHOUETTE, "fp-silhouette-03"),
                                tag(TagType.STYLE, "fp-style-02"))),
                successCase("top-01",
                        tags(tag(TagType.STYLE, "exp-style-04"), tag(TagType.COLOR, "exp-color-04")),
                        tags(tag(TagType.COLOR, "fp-color-01"), tag(TagType.COLOR, "fp-color-02"))),
                successCase("dress-01",
                        tags(tag(TagType.STYLE, "exp-style-05"), tag(TagType.COLOR, "exp-color-05")),
                        tags(tag(TagType.COLOR, "fp-color-03"), tag(TagType.STYLE, "fp-style-03")))
        );
    }

    private static List<CaseFixture> precisionBoundaryCases() {
        return List.of(
                successCase("public-bottom-01",
                        tags(tag(TagType.DETAIL, "포켓"), tag(TagType.STYLE, "exp-style-01")),
                        tags(tag(TagType.DETAIL, "포켓"), tag(TagType.MATERIAL, "fp-material-01"),
                                tag(TagType.DETAIL, "fp-detail-01"),
                                tag(TagType.SILHOUETTE, "fp-silhouette-01"))),
                successCase("ai-bottom-02",
                        tags(tag(TagType.DETAIL, "턱"), tag(TagType.STYLE, "exp-style-02")),
                        tags(tag(TagType.DETAIL, "턱"), tag(TagType.DETAIL, "fp-detail-02"),
                                tag(TagType.SILHOUETTE, "fp-silhouette-02"),
                                tag(TagType.STYLE, "fp-style-01"))),
                successCase("outer-01",
                        tags(tag(TagType.STYLE, "exp-style-03"), tag(TagType.COLOR, "exp-color-03")),
                        tags(tag(TagType.STYLE, "exp-style-03"), tag(TagType.DETAIL, "fp-detail-03"),
                                tag(TagType.SILHOUETTE, "fp-silhouette-03"),
                                tag(TagType.STYLE, "fp-style-02"))),
                successCase("top-01",
                        tags(tag(TagType.STYLE, "exp-style-04")),
                        tags(tag(TagType.STYLE, "exp-style-04"), tag(TagType.DETAIL, "fp-detail-04"),
                                tag(TagType.SILHOUETTE, "fp-silhouette-04"),
                                tag(TagType.COLOR, "fp-color-01"))),
                successCase("dress-01",
                        tags(tag(TagType.STYLE, "exp-style-05"), tag(TagType.COLOR, "exp-color-05")),
                        tags(tag(TagType.STYLE, "exp-style-05"), tag(TagType.COLOR, "exp-color-05"),
                                tag(TagType.STYLE, "fp-style-03"), tag(TagType.COLOR, "fp-color-02")))
        );
    }

    private static Map<String, Object> tamperedPerfectSummary() {
        return Map.of("SHOULD_NOT_BE_TRUSTED", true, "micro", Map.of("precision", 1.0, "recall", 1.0, "f1", 1.0));
    }

    private static Map<String, Object> attempt(
            int attempt, Integer httpStatus, String providerErrorCategory, String xRequestId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempt", attempt);
        result.put("httpStatus", httpStatus);
        result.put("providerErrorCategory", providerErrorCategory);
        result.put("elapsedMillis", attempt);
        result.put("xRequestId", xRequestId);
        return result;
    }

    private static CaseFixture successCase(String imageId, List<Map<String, String>> expected,
                                           List<Map<String, String>> predicted) {
        CaseFixture fixture = new CaseFixture();
        fixture.imageId = imageId;
        fixture.imagePath = "images/" + imageId + ".jpg";
        fixture.expectedCanonicalTags = new ArrayList<>(expected);
        fixture.predictedCanonicalTags = new ArrayList<>(predicted);
        fixture.falseNegatives = List.of();
        fixture.falsePositives = List.of();
        fixture.unknownCanonicalTags = List.of();
        fixture.error = null;
        fixture.providerErrorCategory = null;
        fixture.responseParsingCategory = null;
        fixture.finalStatus = "SUCCESS";
        fixture.attempts = List.of();
        return fixture;
    }

    @SafeVarargs
    private static List<Map<String, String>> tags(Map<String, String>... tags) {
        return new ArrayList<>(List.of(tags));
    }

    private static Map<String, String> tag(TagType type, String name) {
        return new LinkedHashMap<>(Map.of("type", type.name(), "name", name));
    }

    private static String catalogJson() throws Exception {
        return OBJECT_MAPPER.writeValueAsString(catalogEntries());
    }

    private static List<Map<String, String>> catalogEntries() {
        List<Map<String, String>> tags = new ArrayList<>();
        for (CaseFixture fixture : passingCases()) {
            tags.addAll(fixture.expectedCanonicalTags);
            tags.addAll(fixture.predictedCanonicalTags);
        }
        tags.add(tag(TagType.STYLE, "extra-style-01"));
        tags.add(tag(TagType.MATERIAL, "extra-material-01"));
        tags.add(tag(TagType.MATERIAL, "extra-material-02"));
        tags.add(tag(TagType.DETAIL, "extra-detail-01"));
        tags.add(tag(TagType.COLOR, "extra-color-01"));
        tags.add(tag(TagType.SILHOUETTE, "extra-silhouette-01"));
        tags.add(tag(TagType.SILHOUETTE, "extra-silhouette-02"));

        List<Map<String, String>> unique = new ArrayList<>();
        for (Map<String, String> tag : tags) {
            if (!unique.contains(tag)) {
                unique.add(tag);
            }
        }
        Map<TagType, Integer> requiredCounts = Map.of(
                TagType.STYLE, 9,
                TagType.SILHOUETTE, 12,
                TagType.MATERIAL, 8,
                TagType.DETAIL, 10,
                TagType.COLOR, 8
        );
        for (Map.Entry<TagType, Integer> entry : requiredCounts.entrySet()) {
            int index = 1;
            while (unique.stream().filter(tag -> tag.get("type").equals(entry.getKey().name())).count()
                    < entry.getValue()) {
                Map<String, String> filler = tag(
                        entry.getKey(), "filler-" + entry.getKey().name().toLowerCase() + "-" + index++);
                if (!unique.contains(filler)) {
                    unique.add(filler);
                }
            }
        }
        assertThat(unique).hasSize(47);
        return unique;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(Path report, Path catalog) {
    }

    private record GateOutput(int exitCode, String text) {
    }

    @SuppressWarnings("unused")
    static final class ReportFixture {
        public int reportSchemaVersion;
        public Map<String, Object> catalog;
        public String generatedAt;
        public String model;
        public Map<String, Object> summary;
        public List<CaseFixture> cases;
    }

    @SuppressWarnings("unused")
    static final class CaseFixture {
        public String imageId;
        public String imagePath;
        public List<Map<String, String>> expectedCanonicalTags;
        public List<Map<String, String>> predictedCanonicalTags;
        public List<Map<String, String>> falseNegatives;
        public List<Map<String, String>> falsePositives;
        public List<Map<String, String>> unknownCanonicalTags;
        public String error;
        public String providerErrorCategory;
        public String responseParsingCategory;
        public String finalStatus;
        public List<Map<String, Object>> attempts;
    }
}
