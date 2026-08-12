package com.fitback.backend.external.aitag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.external.aitag.openai.OpenAiTagModelClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

        OpenAiTagEvaluationMain.CaseResult result = OpenAiTagEvaluationMain.CaseResult.failed(
                evaluationCase, "ANALYSIS409_1");
        OpenAiTagEvaluationMain.EvaluationSummary summary = OpenAiTagEvaluationMain.summarize(List.of(result));

        assertThat(result.attemptCount()).isZero();
        assertThat(result.attempts()).isEmpty();
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
        assertThat(result.attemptCount()).isEqualTo(1);
        assertThat(result.finalStatus()).isEqualTo("FAILED");
    }

    @Test
    void pacesFiveCasesInOrderWithFourFixedWaitsIncludingAfterAFailedCase() throws Exception {
        List<OpenAiTagEvaluationMain.EvaluationCase> cases = List.of(
                evaluationCase("top-01"),
                evaluationCase("public-bottom-01"),
                evaluationCase("ai-bottom-02"),
                evaluationCase("ai-dress-01"),
                evaluationCase("outer-01")
        );
        List<String> evaluatedCaseIds = new ArrayList<>();
        List<Long> delays = new ArrayList<>();

        List<OpenAiTagEvaluationMain.CaseResult> results = OpenAiTagEvaluationMain.evaluateCases(
                cases,
                evaluationCase -> {
                    evaluatedCaseIds.add(evaluationCase.imageId());
                    return OpenAiTagEvaluationMain.CaseResult.failed(evaluationCase, "ANALYSIS409_1");
                },
                delays::add
        );

        assertThat(OpenAiTagEvaluationMain.INTER_CASE_DELAY_MILLIS).isEqualTo(30_000L);
        assertThat(evaluatedCaseIds).containsExactly(
                "top-01", "public-bottom-01", "ai-bottom-02", "ai-dress-01", "outer-01"
        );
        assertThat(results).extracting(OpenAiTagEvaluationMain.CaseResult::imageId)
                .containsExactlyElementsOf(evaluatedCaseIds);
        assertThat(delays).containsExactly(30_000L, 30_000L, 30_000L, 30_000L);
        assertThat(delays.stream().mapToLong(Long::longValue).sum()).isEqualTo(120_000L);
    }

    @Test
    void stopsBeforeTheNextCaseAndRestoresInterruptFlagWhenPacingIsInterrupted() {
        AtomicInteger evaluatedCases = new AtomicInteger();
        List<OpenAiTagEvaluationMain.EvaluationCase> cases = List.of(
                evaluationCase("top-01"),
                evaluationCase("public-bottom-01")
        );

        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenAiTagEvaluationMain.evaluateCases(
                    cases,
                    evaluationCase -> {
                        evaluatedCases.incrementAndGet();
                        return OpenAiTagEvaluationMain.CaseResult.failed(evaluationCase, "ANALYSIS409_1");
                    },
                    ignoredDelay -> {
                        throw new InterruptedException("interrupted");
                    }
            )).isInstanceOf(InterruptedException.class);

            assertThat(evaluatedCases).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void retries500OnceThenReturnsSuccessWithSingleFinalCase() {
        List<Long> delays = new ArrayList<>();
        AiTagModelResult result = modelResult();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(500, "SERVER_ERROR"),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(result)
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(2);
        assertThat(retryResult.result()).isSameAs(result);
        assertThat(delays).containsExactly(250L);
    }

    @Test
    void retries502Then503BeforeReturningSuccess() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(502, "SERVER_ERROR"),
                        providerFailure(503, "SERVER_ERROR"),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(delays).containsExactly(250L, 500L);
    }

    @Test
    void retriesInvalidModelOutputJsonOnceThenReturnsSuccess() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        invalidModelOutputJsonFailure(),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(2);
        assertThat(delays).containsExactly(250L);
    }

    @Test
    void retriesInvalidModelOutputJsonTwiceThenReturnsSuccessAtGlobalCeiling() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        invalidModelOutputJsonFailure(),
                        invalidModelOutputJsonFailure(),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(delays).containsExactly(250L, 500L);
    }

    @Test
    void stopsAfterThreeInvalidModelOutputJsonFailuresAndKeepsFinalParsingCategory() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        invalidModelOutputJsonFailure(),
                        invalidModelOutputJsonFailure(),
                        invalidModelOutputJsonFailure()
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isFalse();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(retryResult.failure().providerHttpStatus()).isEqualTo(200);
        assertThat(retryResult.failure().responseParsingCategory())
                .isEqualTo("INVALID_MODEL_OUTPUT_JSON");
        assertThat(delays).containsExactly(250L, 500L);
    }

    @Test
    void doesNotRetryInvalidModelOutputSchema() {
        AtomicInteger calls = new AtomicInteger();
        OpenAiTagEvaluationMain.EvaluationFailure schemaFailure =
                new OpenAiTagEvaluationMain.EvaluationFailure(
                        "ANALYSIS409_1",
                        1L,
                        200,
                        null,
                        "INVALID_MODEL_OUTPUT_SCHEMA:EMPTY_GARMENT_TAGS"
                );

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                () -> {
                    calls.incrementAndGet();
                    return OpenAiTagEvaluationMain.EvaluationAttempt.failure(schemaFailure);
                },
                ignoredDelay -> {
                    throw new AssertionError("schema failures must not sleep");
                },
                ignoredBound -> 0L
        );

        assertThat(calls).hasValue(1);
        assertThat(retryResult.attemptCount()).isEqualTo(1);
        assertThat(retryResult.failure()).isEqualTo(schemaFailure);
    }

    @Test
    void doesNotRetryCanonicalFailure() {
        AtomicInteger calls = new AtomicInteger();
        OpenAiTagEvaluationMain.EvaluationFailure canonicalFailure =
                new OpenAiTagEvaluationMain.EvaluationFailure(
                        "ANALYSIS409_1", 1L, null, null, null
                );

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                () -> {
                    calls.incrementAndGet();
                    return OpenAiTagEvaluationMain.EvaluationAttempt.failure(canonicalFailure);
                },
                ignoredDelay -> {
                    throw new AssertionError("canonical failures must not sleep");
                },
                ignoredBound -> 0L
        );

        assertThat(calls).hasValue(1);
        assertThat(retryResult.attemptCount()).isEqualTo(1);
        assertThat(retryResult.failure()).isEqualTo(canonicalFailure);
    }

    @Test
    void sharesGlobalAttemptCeilingFor429ThenInvalidJsonThenSuccess() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", null),
                        invalidModelOutputJsonFailure(),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(delays).containsExactly(5_000L, 500L);
    }

    @Test
    void sharesGlobalAttemptCeilingFor500ThenInvalidJsonThenSuccess() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(500, "SERVER_ERROR"),
                        invalidModelOutputJsonFailure(),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(delays).containsExactly(250L, 500L);
    }

    @Test
    void sharesGlobalAttemptCeilingForInvalidJsonThen429ThenSuccess() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        invalidModelOutputJsonFailure(),
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", null),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(delays).containsExactly(250L, 10_000L);
    }

    @Test
    void stopsBeforeAdditionalProviderCallWhenJsonRetrySleepIsInterrupted() {
        AtomicInteger calls = new AtomicInteger();

        try {
            OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                    () -> {
                        calls.incrementAndGet();
                        return invalidModelOutputJsonFailure();
                    },
                    ignoredDelay -> {
                        throw new InterruptedException("interrupted");
                    },
                    ignoredBound -> 0L
            );

            assertThat(calls).hasValue(1);
            assertThat(retryResult.successful()).isFalse();
            assertThat(retryResult.attemptCount()).isEqualTo(1);
            assertThat(retryResult.failure().error()).isEqualTo("EVALUATION_RETRY_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void keepsJitteredBackoffWithinConfiguredBounds() {
        assertThat(OpenAiTagEvaluationMain.retryDelayMillis(1, bound -> bound - 1L))
                .isEqualTo(500L);
        assertThat(OpenAiTagEvaluationMain.retryDelayMillis(2, bound -> bound - 1L))
                .isEqualTo(1_000L);
    }

    @Test
    void honorsBoundedRetryAfterForEvaluator429ThenReturnsSuccess() {
        List<Long> delays = new ArrayList<>();
        OpenAiTagModelClient.RateLimitMetadata metadata = rateLimitMetadata(
                56_000L, 0L, 149_984L, 1_000L, 360_000L, 0L, 3_000L
        );

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", metadata),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(2);
        assertThat(delays).containsExactly(56_250L);
        assertThat(retryResult.attempts().getFirst().providerErrorCode())
                .isEqualTo("rate_limit_exceeded");
        assertThat(retryResult.attempts().getFirst().rateLimitMetadata()).isEqualTo(metadata);
    }

    @Test
    void allowsTwoRateLimitSleepsAtTheExactTotalBudgetBoundary() {
        List<Long> delays = new ArrayList<>();
        OpenAiTagModelClient.RateLimitMetadata metadata = rateLimitMetadata(
                60_000L, null, null, null, null, null, null
        );

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", metadata),
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", metadata),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                bound -> bound - 1L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(delays).containsExactly(60_500L, 60_500L);
        assertThat(delays.stream().mapToLong(Long::longValue).sum()).isEqualTo(121_000L);
    }

    @Test
    void usesExponentialBoundedFallbackForRateLimitCodeWithoutHeaders() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", null),
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", null),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(delays).containsExactly(5_000L, 10_000L);
    }

    @Test
    void usesResetOnlyForAnExhaustedRateLimitDimension() {
        List<Long> delays = new ArrayList<>();
        OpenAiTagModelClient.RateLimitMetadata metadata = rateLimitMetadata(
                null, 12L, 99L, 1_000L, 2_000L, 0L, 3_000L
        );

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(429, "RATE_LIMIT", "UNKNOWN", metadata),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isTrue();
        assertThat(delays).containsExactly(3_250L);
    }

    @Test
    void doesNotRetryQuotaLimitsOrRateLimitWaitsBeyondTheEvaluatorBound() {
        List<OpenAiTagEvaluationMain.EvaluationFailure> failures = List.of(
                providerFailureValue(429, "RATE_LIMIT", "credit_balance_exhausted", null),
                providerFailureValue(429, "RATE_LIMIT", "organization_spend_limit_exceeded", null),
                providerFailureValue(429, "RATE_LIMIT", "project_spend_limit_exceeded", null),
                providerFailureValue(429, "RATE_LIMIT", "organization_usage_limit_exceeded", null),
                providerFailureValue(429, "RATE_LIMIT", "insufficient_quota", null),
                providerFailureValue(
                        429, "RATE_LIMIT", "rate_limit_exceeded",
                        rateLimitMetadata(60_001L, 0L, 0L, null, null, null, null)
                )
        );

        for (OpenAiTagEvaluationMain.EvaluationFailure failure : failures) {
            AtomicInteger calls = new AtomicInteger();
            OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                    () -> {
                        calls.incrementAndGet();
                        return OpenAiTagEvaluationMain.EvaluationAttempt.failure(failure);
                    },
                    ignoredDelay -> {
                        throw new AssertionError("non-retryable 429 must not sleep");
                    },
                    ignoredBound -> 0L
            );

            assertThat(calls).hasValue(1);
            assertThat(retryResult.attemptCount()).isEqualTo(1);
            assertThat(retryResult.failure()).isEqualTo(failure);
        }
    }

    @Test
    void stopsAfterThree504FailuresAndKeepsFinalProviderMetadata() {
        List<Long> delays = new ArrayList<>();

        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(504, "SERVER_ERROR"),
                        providerFailure(504, "SERVER_ERROR"),
                        providerFailure(504, "SERVER_ERROR")
                ),
                delays::add,
                ignoredBound -> 0L
        );

        assertThat(retryResult.successful()).isFalse();
        assertThat(retryResult.attemptCount()).isEqualTo(3);
        assertThat(retryResult.failure().providerHttpStatus()).isEqualTo(504);
        assertThat(retryResult.failure().providerErrorCategory()).isEqualTo("SERVER_ERROR");
        assertThat(delays).containsExactly(250L, 500L);
    }

    @Test
    void stopsRetryingAndRestoresInterruptFlagWhenBackoffIsInterrupted() {
        try {
            OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                    sequence(providerFailure(500, "SERVER_ERROR")),
                    ignoredDelay -> {
                        throw new InterruptedException("interrupted");
                    },
                    ignoredBound -> 0L
            );

            assertThat(retryResult.successful()).isFalse();
            assertThat(retryResult.attemptCount()).isEqualTo(1);
            assertThat(retryResult.failure().error()).isEqualTo("EVALUATION_RETRY_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNotRetryNonTransientProviderOrParsingFailures() {
        List<OpenAiTagEvaluationMain.EvaluationFailure> failures = List.of(
                new OpenAiTagEvaluationMain.EvaluationFailure("ANALYSIS409_1", 1L, 400, "CLIENT_ERROR", null),
                new OpenAiTagEvaluationMain.EvaluationFailure("ANALYSIS409_1", 1L, 429, "RATE_LIMIT", null),
                new OpenAiTagEvaluationMain.EvaluationFailure("ANALYSIS409_1", 1L, null, "TIMEOUT", null),
                new OpenAiTagEvaluationMain.EvaluationFailure("ANALYSIS409_1", 1L, null, "TRANSPORT_ERROR", null),
                new OpenAiTagEvaluationMain.EvaluationFailure(
                        "ANALYSIS409_1", 1L, 200, null, "INVALID_RESPONSE_JSON"),
                new OpenAiTagEvaluationMain.EvaluationFailure(
                        "ANALYSIS409_1", 1L, 200, null, "INVALID_MODEL_OUTPUT_SCHEMA")
        );

        for (OpenAiTagEvaluationMain.EvaluationFailure failure : failures) {
            AtomicInteger calls = new AtomicInteger();
            OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                    () -> {
                        calls.incrementAndGet();
                        return OpenAiTagEvaluationMain.EvaluationAttempt.failure(failure);
                    },
                    ignoredDelay -> {
                        throw new AssertionError("non-transient failure must not sleep");
                    },
                    ignoredBound -> 0L
            );

            assertThat(calls).hasValue(1);
            assertThat(retryResult.attemptCount()).isEqualTo(1);
            assertThat(retryResult.failure()).isEqualTo(failure);
        }
    }

    @Test
    void recordsAttemptCountAndFinalStatusWithoutDuplicatingSummaryCases() {
        OpenAiTagEvaluationMain.EvaluationCase evaluationCase = new OpenAiTagEvaluationMain.EvaluationCase(
                "top-01", "images/top-01.jpeg", List.of(
                        new AiTagPrediction(TagType.STYLE, "캐주얼")));
        OpenAiTagEvaluationMain.CaseResult result = OpenAiTagEvaluationMain.successfulCase(
                evaluationCase, modelResult(), Set.<OpenAiTagEvaluationMain.TagKey>of(), 2L, 2);

        assertThat(result.attemptCount()).isEqualTo(2);
        assertThat(result.finalStatus()).isEqualTo("SUCCESS");
        assertThat(OpenAiTagEvaluationMain.summarize(List.of(result)).totalCases()).isEqualTo(1);
        assertThat(OpenAiTagEvaluationMain.summarize(List.of(result)).successfulCases()).isEqualTo(1);
    }

    @Test
    void serializesOnlySafeRetryMetadata() throws Exception {
        OpenAiTagEvaluationMain.EvaluationCase evaluationCase = new OpenAiTagEvaluationMain.EvaluationCase(
                "top-01", "images/top-01.jpeg", List.of(
                        new AiTagPrediction(TagType.STYLE, "캐주얼")));
        OpenAiTagEvaluationMain.CaseResult result = OpenAiTagEvaluationMain.CaseResult.failed(
                evaluationCase, "ANALYSIS409_1", 10L, 500, "SERVER_ERROR", null, 8L, 3);

        String serialized = new ObjectMapper().writeValueAsString(result);

        assertThat(serialized)
                .contains("attemptCount", "finalStatus", "SERVER_ERROR")
                .doesNotContain("provider-response", "test-key", "data:image", "imageBytes");
    }

    @Test
    void serializesSafeMetadataForEveryProviderAttempt() throws Exception {
        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(500, "SERVER_ERROR", "req-1"),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult("req-2"))
                ),
                ignoredDelay -> { },
                ignoredBound -> 0L
        );
        OpenAiTagEvaluationMain.EvaluationCase evaluationCase = new OpenAiTagEvaluationMain.EvaluationCase(
                "top-01", "images/top-01.jpeg", List.of(new AiTagPrediction(TagType.STYLE, "캐주얼")));
        OpenAiTagEvaluationMain.CaseResult result = OpenAiTagEvaluationMain.successfulCase(
                evaluationCase, retryResult.result(), Set.of(), 8L, retryResult.attemptCount(),
                retryResult.attempts());

        String serialized = new ObjectMapper().writeValueAsString(result);

        assertThat(result.attempts()).containsExactly(
                new OpenAiTagEvaluationMain.AttemptMetadata(1, 500, "SERVER_ERROR", 1L, "req-1"),
                new OpenAiTagEvaluationMain.AttemptMetadata(2, null, null, 100L, "req-2")
        );
        assertThat(serialized)
                .contains("\"attempt\":1", "\"httpStatus\":500", "\"xRequestId\":\"req-1\"")
                .contains("\"attempt\":2", "\"xRequestId\":\"req-2\"")
                .doesNotContain("provider-response", "test-key", "data:image", "imageBytes");
    }

    @Test
    void serializesOnlyAllowlistedNumericRateLimitEvidence() throws Exception {
        OpenAiTagModelClient.RateLimitMetadata metadata = rateLimitMetadata(
                56_000L, 0L, 149_984L, 1_000L, 360_000L, 0L, 3_000L
        );
        OpenAiTagEvaluationMain.RetryResult retryResult = OpenAiTagEvaluationMain.analyzeWithRetry(
                sequence(
                        providerFailure(429, "RATE_LIMIT", "rate_limit_exceeded", metadata),
                        OpenAiTagEvaluationMain.EvaluationAttempt.success(modelResult())
                ),
                ignoredDelay -> { },
                ignoredBound -> 0L
        );

        String serialized = new ObjectMapper().writeValueAsString(retryResult.attempts());

        assertThat(serialized)
                .contains(
                        "\"providerErrorCode\":\"rate_limit_exceeded\"",
                        "\"retryAfterMillis\":56000",
                        "\"remainingProjectTokens\":0"
                )
                .doesNotContain(
                        "provider-secret", "error.message", "authorization", "apiKey", "rawHeaders"
                );
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
    void rejectsAsciiAndUnicodeBoundaryWhitespaceInsteadOfNormalizing(@TempDir Path directory) throws Exception {
        Path dataset = directory.resolve("gold-labels.json");
        for (String name : List.of(
                " 캐주얼", "캐주얼 ",
                "\u2003캐주얼", "캐주얼\u2003",
                "\u00A0캐주얼", "캐주얼\u00A0")) {
            Files.writeString(dataset, """
                    {
                      "cases": [{
                        "imageId": "top-01",
                        "imagePath": "images/top-01.jpg",
                        "expectedCanonicalTags": [{"type": "STYLE", "name": "%s"}]
                      }]
                    }
                    """.formatted(name));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> OpenAiTagEvaluationMain.readDataset(dataset)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("name must not have leading or trailing whitespace");
        }
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

    @Test
    void reportSchemaV2IncludesSafeCatalogIdentityForExactCatalogBytes(@TempDir Path directory) throws Exception {
        Path catalogPath = directory.resolve("catalog.json");
        Files.writeString(catalogPath, """
                [
                  {"type": "STYLE", "name": "캐주얼"},
                  {"type": "DETAIL", "name": "포켓"}
                ]
                """);
        OpenAiTagEvaluationMain.CatalogSnapshot catalog =
                OpenAiTagEvaluationMain.readCatalogSnapshot(catalogPath);
        OpenAiTagEvaluationMain.EvaluationReport report = new OpenAiTagEvaluationMain.EvaluationReport(
                OpenAiTagEvaluationMain.REPORT_SCHEMA_VERSION,
                catalog.identity(),
                "2026-08-11T00:00:00Z",
                "gpt-5.6-luna",
                OpenAiTagEvaluationMain.summarize(List.of()),
                List.of());

        String serialized = new ObjectMapper().writeValueAsString(report);

        assertThat(report.catalog().identityVersion()).isEqualTo(OpenAiTagEvaluationMain.CATALOG_IDENTITY_VERSION);
        assertThat(report.catalog().tagCount()).isEqualTo(2);
        assertThat(report.catalog().sha256()).matches("[0-9a-f]{64}");
        assertThat(serialized)
                .contains("\"reportSchemaVersion\":2", "\"identityVersion\":1", "\"tagCount\":2")
                .doesNotContain(catalogPath.toString(), "test-key", "data:image", "x-request-id", "model-output");
    }

    @Test
    void catalogIdentityIsDeterministicForExactBytesAndChangesWhenBytesChange(@TempDir Path directory)
            throws Exception {
        String catalogJson = """
                [
                  {"type": "STYLE", "name": "캐주얼"},
                  {"type": "DETAIL", "name": "포켓"}
                ]
                """;
        Path firstPath = directory.resolve("catalog-first.json");
        Path identicalPath = directory.resolve("catalog-identical.json");
        Path changedPath = directory.resolve("catalog-changed.json");
        Files.writeString(firstPath, catalogJson);
        Files.writeString(identicalPath, catalogJson);
        Files.writeString(changedPath, catalogJson.stripTrailing());
        OpenAiTagEvaluationMain.CatalogIdentity first =
                OpenAiTagEvaluationMain.readCatalogSnapshot(firstPath).identity();
        OpenAiTagEvaluationMain.CatalogIdentity identical =
                OpenAiTagEvaluationMain.readCatalogSnapshot(identicalPath).identity();
        OpenAiTagEvaluationMain.CatalogIdentity changed =
                OpenAiTagEvaluationMain.readCatalogSnapshot(changedPath).identity();

        assertThat(identical).isEqualTo(first);
        assertThat(changed.tagCount()).isEqualTo(first.tagCount());
        assertThat(changed.sha256()).isNotEqualTo(first.sha256());
    }

    private static OpenAiTagEvaluationMain.EvaluationCall sequence(
            OpenAiTagEvaluationMain.EvaluationAttempt... attempts
    ) {
        AtomicInteger index = new AtomicInteger();
        return () -> attempts[index.getAndIncrement()];
    }

    private static OpenAiTagEvaluationMain.EvaluationCase evaluationCase(String imageId) {
        return new OpenAiTagEvaluationMain.EvaluationCase(
                imageId,
                "images/" + imageId + ".jpeg",
                List.of(new AiTagPrediction(TagType.STYLE, "캐주얼"))
        );
    }

    private static OpenAiTagEvaluationMain.EvaluationAttempt providerFailure(
            int status, String category
    ) {
        return providerFailure(status, category, "UNAVAILABLE");
    }

    private static OpenAiTagEvaluationMain.EvaluationAttempt invalidModelOutputJsonFailure() {
        return OpenAiTagEvaluationMain.EvaluationAttempt.failure(
                new OpenAiTagEvaluationMain.EvaluationFailure(
                        "ANALYSIS409_1", 1L, 200, null, "INVALID_MODEL_OUTPUT_JSON"
                )
        );
    }

    private static OpenAiTagEvaluationMain.EvaluationAttempt providerFailure(
            int status, String category, String xRequestId
    ) {
        return OpenAiTagEvaluationMain.EvaluationAttempt.failure(
                new OpenAiTagEvaluationMain.EvaluationFailure(
                        "ANALYSIS409_1", 1L, status, category, null, xRequestId));
    }

    private static OpenAiTagEvaluationMain.EvaluationAttempt providerFailure(
            int status,
            String category,
            String providerErrorCode,
            OpenAiTagModelClient.RateLimitMetadata rateLimitMetadata
    ) {
        return OpenAiTagEvaluationMain.EvaluationAttempt.failure(providerFailureValue(
                status, category, providerErrorCode, rateLimitMetadata
        ));
    }

    private static OpenAiTagEvaluationMain.EvaluationFailure providerFailureValue(
            int status,
            String category,
            String providerErrorCode,
            OpenAiTagModelClient.RateLimitMetadata rateLimitMetadata
    ) {
        return new OpenAiTagEvaluationMain.EvaluationFailure(
                "ANALYSIS409_1", 1L, status, category, null, "UNAVAILABLE",
                providerErrorCode, rateLimitMetadata
        );
    }

    private static OpenAiTagModelClient.RateLimitMetadata rateLimitMetadata(
            Long retryAfterMillis,
            Long remainingRequests,
            Long remainingTokens,
            Long resetRequestsMillis,
            Long resetTokensMillis,
            Long remainingProjectTokens,
            Long resetProjectTokensMillis
    ) {
        return new OpenAiTagModelClient.RateLimitMetadata(
                retryAfterMillis, remainingRequests, remainingTokens, resetRequestsMillis,
                resetTokensMillis, remainingProjectTokens, resetProjectTokensMillis
        );
    }

    private static AiTagModelResult modelResult() {
        return modelResult("UNAVAILABLE");
    }

    private static AiTagModelResult modelResult(String xRequestId) {
        return new AiTagModelResult(
                "openai", "gpt-5.6-luna", List.of(new AiTagGarment(
                        GarmentPiece.TOP,
                        List.of(new AiTagPrediction(TagType.STYLE, "캐주얼")),
                        List.of()
                )),
                10, 5, 100, xRequestId
        );
    }
}
