package com.fitback.backend.global.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in recommendation latency trace. Values in this trace are intentionally limited to
 * durations, counts, safe identifiers, and a generated correlation ID.
 */
public final class RecommendationPerformanceTrace {

    public static final String REQUEST_HEADER = "X-Fitback-Benchmark-Trace";
    public static final String REQUEST_VALUE = "baseline-v1";
    public static final String RESPONSE_HEADER = "X-Fitback-Benchmark-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(
            RecommendationPerformanceTrace.class
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<ActiveTrace> ACTIVE = new ThreadLocal<>();

    private RecommendationPerformanceTrace() {
    }

    public static Scope beginIfRequested(String requestValue) {
        if (!REQUEST_VALUE.equals(requestValue) || ACTIVE.get() != null) {
            return Scope.inactive();
        }
        ActiveTrace trace = new ActiveTrace(UUID.randomUUID().toString(), System.nanoTime());
        ACTIVE.set(trace);
        return new Scope(trace);
    }

    public static <T> T measureStage(String stageName, Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        ActiveTrace trace = ACTIVE.get();
        if (trace == null) {
            return action.get();
        }
        long startedAt = System.nanoTime();
        try {
            return action.get();
        } finally {
            trace.recordStage(stageName, startedAt, System.nanoTime());
        }
    }

    public static void measureStage(String stageName, Runnable action) {
        measureStage(stageName, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T measureSearchCatalog(String tagKind, Supplier<T> action) {
        return measureCatalogCall(CatalogCallType.SEARCH, tagKind, 1, action);
    }

    public static <T> T measureLookupCatalog(int inputSize, Supplier<T> action) {
        return measureCatalogCall(CatalogCallType.LOOKUP, null, inputSize, action);
    }

    public static void recordCandidateCounts(
            int searchedCandidateCount,
            int categoryFilteredCandidateCount,
            int selectedCandidateCount
    ) {
        ActiveTrace trace = ACTIVE.get();
        if (trace != null) {
            trace.recordCandidateCounts(
                    searchedCandidateCount,
                    categoryFilteredCandidateCount,
                    selectedCandidateCount
            );
        }
    }

    public static void recordBrowserRerankingCandidateCount(int candidateCount) {
        ActiveTrace trace = ACTIVE.get();
        if (trace != null) {
            trace.recordBrowserRerankingCandidateCount(candidateCount);
        }
    }

    private static <T> T measureCatalogCall(
            CatalogCallType callType,
            String tagKind,
            int inputSize,
            Supplier<T> action
    ) {
        Objects.requireNonNull(action, "action must not be null");
        ActiveTrace trace = ACTIVE.get();
        if (trace == null) {
            return action.get();
        }
        long startedAt = System.nanoTime();
        boolean succeeded = false;
        try {
            T result = action.get();
            succeeded = true;
            return result;
        } finally {
            trace.recordCatalogCall(
                    callType,
                    tagKind,
                    inputSize,
                    startedAt,
                    System.nanoTime(),
                    succeeded
            );
        }
    }

    public static final class Scope implements AutoCloseable {

        private static final Scope INACTIVE = new Scope(null);

        private final ActiveTrace trace;
        private boolean closed;

        private Scope(ActiveTrace trace) {
            this.trace = trace;
        }

        private static Scope inactive() {
            return INACTIVE;
        }

        public boolean active() {
            return trace != null;
        }

        public String traceId() {
            return trace == null ? null : trace.traceId;
        }

        public void complete(int httpStatus) {
            if (trace != null) {
                trace.complete(httpStatus, "SUCCESS");
            }
        }

        public void fail(Integer httpStatus) {
            if (trace != null) {
                trace.complete(httpStatus, "ERROR");
            }
        }

        public Snapshot snapshot() {
            return trace == null ? null : trace.snapshot();
        }

        public String toStructuredJson() {
            return trace == null ? null : trace.toStructuredJson();
        }

        @Override
        public void close() {
            if (trace == null || closed) {
                return;
            }
            closed = true;
            if (ACTIVE.get() == trace) {
                ACTIVE.remove();
            }
            log.info("recommendation_performance_trace={}", trace.toStructuredJson());
        }
    }

    public record Snapshot(
            String traceId,
            String outcome,
            Integer httpStatus,
            long requestWallClockMs,
            List<CatalogCall> searchCatalogCalls,
            Timing searchCatalogTiming,
            List<CatalogCall> lookupCatalogCalls,
            Timing lookupCatalogTiming,
            Map<String, Timing> stages,
            CandidateCounts candidateCounts,
            int browserRerankingCandidateCount
    ) {
    }

    public record CatalogCall(
            String tagKind,
            int inputSize,
            long wallClockMs,
            boolean succeeded
    ) {
    }

    public record Timing(int invocationCount, long wallClockMs, long cumulativeMs) {
    }

    public record CandidateCounts(
            int searchedCandidateCount,
            int categoryFilteredCandidateCount,
            int selectedCandidateCount
    ) {
    }

    private record CatalogLog(
            int count,
            Timing timing,
            List<CatalogCall> calls
    ) {
    }

    private record LogPayload(
            String event,
            String schemaVersion,
            String traceId,
            String outcome,
            Integer httpStatus,
            long requestWallClockMs,
            CatalogLog searchCatalog,
            CatalogLog lookupCatalog,
            Map<String, Timing> stages,
            CandidateCounts candidateCounts,
            int browserRerankingCandidateCount
    ) {
    }

    private enum CatalogCallType {
        SEARCH,
        LOOKUP
    }

    private static final class ActiveTrace {

        private final String traceId;
        private final long requestStartedAt;
        private final List<CatalogCall> searchCatalogCalls = new ArrayList<>();
        private final List<CatalogCall> lookupCatalogCalls = new ArrayList<>();
        private final TimingAccumulator searchCatalogTiming = new TimingAccumulator();
        private final TimingAccumulator lookupCatalogTiming = new TimingAccumulator();
        private final Map<String, TimingAccumulator> stages = new LinkedHashMap<>();
        private CandidateCounts candidateCounts;
        private int browserRerankingCandidateCount = -1;
        private String outcome = "IN_PROGRESS";
        private Integer httpStatus;

        private ActiveTrace(String traceId, long requestStartedAt) {
            this.traceId = traceId;
            this.requestStartedAt = requestStartedAt;
        }

        private void recordStage(String stageName, long startedAt, long completedAt) {
            stages.computeIfAbsent(safeIdentifier(stageName), ignored -> new TimingAccumulator())
                    .add(startedAt, completedAt);
        }

        private void recordCatalogCall(
                CatalogCallType callType,
                String tagKind,
                int inputSize,
                long startedAt,
                long completedAt,
                boolean succeeded
        ) {
            long wallClockMs = elapsedMilliseconds(startedAt, completedAt);
            CatalogCall call = new CatalogCall(
                    callType == CatalogCallType.SEARCH ? safeIdentifier(tagKind) : null,
                    Math.max(0, inputSize),
                    wallClockMs,
                    succeeded
            );
            if (callType == CatalogCallType.SEARCH) {
                searchCatalogCalls.add(call);
                searchCatalogTiming.add(startedAt, completedAt);
                return;
            }
            lookupCatalogCalls.add(call);
            lookupCatalogTiming.add(startedAt, completedAt);
        }

        private void recordCandidateCounts(
                int searchedCandidateCount,
                int categoryFilteredCandidateCount,
                int selectedCandidateCount
        ) {
            candidateCounts = new CandidateCounts(
                    Math.max(0, searchedCandidateCount),
                    Math.max(0, categoryFilteredCandidateCount),
                    Math.max(0, selectedCandidateCount)
            );
        }

        private void recordBrowserRerankingCandidateCount(int candidateCount) {
            browserRerankingCandidateCount = Math.max(0, candidateCount);
        }

        private void complete(Integer responseStatus, String completedOutcome) {
            httpStatus = responseStatus;
            outcome = completedOutcome;
        }

        private Snapshot snapshot() {
            Map<String, Timing> stageSnapshot = new LinkedHashMap<>();
            stages.forEach((name, timing) -> stageSnapshot.put(name, timing.snapshot()));
            return new Snapshot(
                    traceId,
                    outcome,
                    httpStatus,
                    elapsedMilliseconds(requestStartedAt, System.nanoTime()),
                    List.copyOf(searchCatalogCalls),
                    searchCatalogTiming.snapshot(),
                    List.copyOf(lookupCatalogCalls),
                    lookupCatalogTiming.snapshot(),
                    Collections.unmodifiableMap(stageSnapshot),
                    candidateCounts,
                    browserRerankingCandidateCount
            );
        }

        private String toStructuredJson() {
            Snapshot snapshot = snapshot();
            try {
                return OBJECT_MAPPER.writeValueAsString(new LogPayload(
                        "recommendation_performance_trace",
                        "baseline-v1",
                        snapshot.traceId(),
                        snapshot.outcome(),
                        snapshot.httpStatus(),
                        snapshot.requestWallClockMs(),
                        new CatalogLog(
                                snapshot.searchCatalogCalls().size(),
                                snapshot.searchCatalogTiming(),
                                snapshot.searchCatalogCalls()
                        ),
                        new CatalogLog(
                                snapshot.lookupCatalogCalls().size(),
                                snapshot.lookupCatalogTiming(),
                                snapshot.lookupCatalogCalls()
                        ),
                        snapshot.stages(),
                        snapshot.candidateCounts(),
                        snapshot.browserRerankingCandidateCount()
                ));
            } catch (RuntimeException exception) {
                return "{\"event\":\"recommendation_performance_trace\","
                        + "\"schemaVersion\":\"baseline-v1\","
                        + "\"outcome\":\"SERIALIZATION_ERROR\"}";
            }
        }
    }

    private static final class TimingAccumulator {

        private final List<Interval> intervals = new ArrayList<>();

        private void add(long startedAt, long completedAt) {
            intervals.add(new Interval(startedAt, Math.max(startedAt, completedAt)));
        }

        private Timing snapshot() {
            if (intervals.isEmpty()) {
                return new Timing(0, 0, 0);
            }
            long cumulativeNanos = intervals.stream()
                    .mapToLong(interval -> interval.completedAt - interval.startedAt)
                    .sum();
            List<Interval> ordered = intervals.stream()
                    .sorted(Comparator.comparingLong(Interval::startedAt))
                    .toList();
            long wallNanos = 0;
            long activeStart = ordered.getFirst().startedAt;
            long activeEnd = ordered.getFirst().completedAt;
            for (Interval interval : ordered.subList(1, ordered.size())) {
                if (interval.startedAt > activeEnd) {
                    wallNanos += activeEnd - activeStart;
                    activeStart = interval.startedAt;
                    activeEnd = interval.completedAt;
                    continue;
                }
                activeEnd = Math.max(activeEnd, interval.completedAt);
            }
            wallNanos += activeEnd - activeStart;
            return new Timing(
                    intervals.size(),
                    nanosToMilliseconds(wallNanos),
                    nanosToMilliseconds(cumulativeNanos)
            );
        }
    }

    private record Interval(long startedAt, long completedAt) {
    }

    private static String safeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "UNSPECIFIED";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static long elapsedMilliseconds(long startedAt, long completedAt) {
        return nanosToMilliseconds(Math.max(0, completedAt - startedAt));
    }

    private static long nanosToMilliseconds(long nanos) {
        return Math.max(0, nanos / 1_000_000L);
    }
}
