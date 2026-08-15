package com.fitback.backend.global.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecommendationPerformanceTraceTest {

    @Test
    void recordsSafeCatalogCountsAndSeparatesWallClockFromCumulativeTiming() throws Exception {
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            RecommendationPerformanceTrace.measureSearchCatalog(
                    new RecommendationPerformanceTrace.SearchCatalogCallInput(
                            1,
                            "hmac-sha256:" + "b".repeat(64),
                            "COLOR",
                            "DRESS"
                    ),
                    () -> new SearchCounts(20, 14),
                    SearchCounts::rawResultCount
            );
            RecommendationPerformanceTrace.recordCategoryFilteredResultCount(1, 14);
            RecommendationPerformanceTrace.measureLookupCatalog(1, () -> "lookup-result");
            RecommendationPerformanceTrace.measureStage("candidateMergeDedup", () -> null);
            RecommendationPerformanceTrace.recordCandidateCounts(20, 14, 10);
            RecommendationPerformanceTrace.recordSelectorCounts(14, 14, 10, 1, 1, 1, 0, 0);
            RecommendationPerformanceTrace.recordBrowserRerankingCandidateCount(10);
            scope.complete(200);

            RecommendationPerformanceTrace.Snapshot snapshot = scope.snapshot();

            assertThat(scope.active()).isTrue();
            assertThat(snapshot.requestWallClockMs()).isGreaterThanOrEqualTo(0);
            assertThat(snapshot.searchCatalogCalls())
                    .singleElement()
                    .satisfies(call -> {
                        assertThat(call.queryIndex()).isEqualTo(1);
                        assertThat(call.queryFingerprint()).isEqualTo(
                                "hmac-sha256:" + "b".repeat(64)
                        );
                        assertThat(call.category()).isEqualTo("DRESS");
                        assertThat(call.tagKind()).isEqualTo("COLOR");
                        assertThat(call.inputSize()).isEqualTo(1);
                        assertThat(call.rawResultCount()).isEqualTo(20);
                        assertThat(call.categoryFilteredResultCount()).isEqualTo(14);
                        assertThat(call.providerSucceeded()).isTrue();
                        assertThat(call.wallClockMs()).isGreaterThanOrEqualTo(0);
                    });
            assertThat(snapshot.lookupCatalogCalls())
                    .singleElement()
                    .satisfies(call -> assertThat(call.inputSize()).isEqualTo(1));
            assertThat(snapshot.stages().get("candidateMergeDedup"))
                    .satisfies(timing -> {
                        assertThat(timing.wallClockMs()).isGreaterThanOrEqualTo(0);
                        assertThat(timing.cumulativeMs()).isGreaterThanOrEqualTo(0);
                    });
            assertThat(snapshot.candidateCounts()).isEqualTo(
                    new RecommendationPerformanceTrace.CandidateCounts(20, 14, 10)
            );
            assertThat(snapshot.selectorCounts()).isEqualTo(
                    new RecommendationPerformanceTrace.SelectorCounts(14, 14, 10, 1, 1, 1, 0, 0)
            );
            assertThat(snapshot.browserRerankingCandidateCount()).isEqualTo(10);
            assertThat(snapshot.httpStatus()).isEqualTo(200);
            assertThat(scope.toStructuredJson())
                    .doesNotContain(
                            "candidateId",
                            "imageUrl",
                            "embedding",
                            "token",
                            "네이비"
                    );
            assertThat(new ObjectMapper().readTree(scope.toStructuredJson())
                    .path("searchCatalog")
                    .path("count")
                    .asInt()).isEqualTo(1);
            assertThat(new ObjectMapper().readTree(scope.toStructuredJson())
                    .path("searchCatalog")
                    .path("calls")
                    .get(0)
                    .path("rawResultCount")
                    .asInt()).isEqualTo(20);
        }
    }

    @Test
    void ignoresRequestsWithoutTheExactBenchmarkHeaderValue() {
        AtomicBoolean invoked = new AtomicBoolean();
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested("anything-else")) {
            SearchCounts result = RecommendationPerformanceTrace.measureSearchCatalog(
                    new RecommendationPerformanceTrace.SearchCatalogCallInput(
                            1,
                            "hmac-sha256:" + "c".repeat(64),
                            "COLOR",
                            "TOP"
                    ),
                    () -> {
                        invoked.set(true);
                        return new SearchCounts(1, 1);
                    },
                    SearchCounts::rawResultCount
            );

            assertThat(scope.active()).isFalse();
            assertThat(scope.snapshot()).isNull();
            assertThat(scope.toStructuredJson()).isNull();
            assertThat(result).isEqualTo(new SearchCounts(1, 1));
            assertThat(invoked).isTrue();
        }
    }

    @Test
    void omitsUnknownHttpStatusForAnErrorTrace() {
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            scope.fail(null);

            RecommendationPerformanceTrace.Snapshot snapshot = scope.snapshot();

            assertThat(snapshot.outcome()).isEqualTo("ERROR");
            assertThat(snapshot.httpStatus()).isNull();
        }
    }

    @Test
    void recordsProviderFailureWithoutRecordingSearchInput() {
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            assertThatThrownBy(() -> RecommendationPerformanceTrace.measureSearchCatalog(
                    new RecommendationPerformanceTrace.SearchCatalogCallInput(
                            2,
                            "hmac-sha256:" + "d".repeat(64),
                            "DETAIL",
                            "DRESS"
                    ),
                    () -> {
                        throw new IllegalStateException("provider failed");
                    },
                    ignored -> 0
            )).isInstanceOf(IllegalStateException.class);

            RecommendationPerformanceTrace.Snapshot snapshot = scope.snapshot();

            assertThat(snapshot.searchCatalogCalls())
                    .singleElement()
                    .satisfies(call -> {
                        assertThat(call.queryIndex()).isEqualTo(2);
                        assertThat(call.rawResultCount()).isNull();
                        assertThat(call.categoryFilteredResultCount()).isNull();
                        assertThat(call.providerSucceeded()).isFalse();
                    });
            assertThat(scope.toStructuredJson()).doesNotContain("민감한 내부 검색어");
        }
    }

    @Test
    void recordsBatchLookupInputSizeWithoutChangingTheTraceSchema() throws Exception {
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            RecommendationPerformanceTrace.measureLookupCatalog(10, java.util.Map::of);
            scope.complete(200);

            RecommendationPerformanceTrace.Snapshot snapshot = scope.snapshot();

            assertThat(snapshot.lookupCatalogCalls())
                    .singleElement()
                    .satisfies(call -> assertThat(call.inputSize()).isEqualTo(10));
            assertThat(new ObjectMapper().readTree(scope.toStructuredJson())
                    .path("lookupCatalog")
                    .path("calls")
                    .get(0)
                    .path("inputSize")
                    .asInt()).isEqualTo(10);
        }
    }

    private record SearchCounts(int rawResultCount, int categoryFilteredResultCount) {
    }
}
