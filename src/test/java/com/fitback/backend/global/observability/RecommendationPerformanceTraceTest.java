package com.fitback.backend.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

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
                    "SILHOUETTE",
                    () -> "search-result"
            );
            RecommendationPerformanceTrace.measureLookupCatalog(1, () -> "lookup-result");
            RecommendationPerformanceTrace.measureStage("candidateMergeDedup", () -> null);
            RecommendationPerformanceTrace.recordCandidateCounts(20, 14, 10);
            RecommendationPerformanceTrace.recordBrowserRerankingCandidateCount(10);
            scope.complete(200);

            RecommendationPerformanceTrace.Snapshot snapshot = scope.snapshot();

            assertThat(scope.active()).isTrue();
            assertThat(snapshot.requestWallClockMs()).isGreaterThanOrEqualTo(0);
            assertThat(snapshot.searchCatalogCalls())
                    .singleElement()
                    .satisfies(call -> {
                        assertThat(call.tagKind()).isEqualTo("SILHOUETTE");
                        assertThat(call.inputSize()).isEqualTo(1);
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
            assertThat(snapshot.browserRerankingCandidateCount()).isEqualTo(10);
            assertThat(snapshot.httpStatus()).isEqualTo(200);
            assertThat(scope.toStructuredJson())
                    .doesNotContain("candidateId", "imageUrl", "embedding", "token");
            assertThat(new ObjectMapper().readTree(scope.toStructuredJson())
                    .path("searchCatalog")
                    .path("count")
                    .asInt()).isEqualTo(1);
        }
    }

    @Test
    void ignoresRequestsWithoutTheExactBenchmarkHeaderValue() {
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested("anything-else")) {
            RecommendationPerformanceTrace.measureSearchCatalog("COLOR", () -> "ignored");

            assertThat(scope.active()).isFalse();
            assertThat(scope.snapshot()).isNull();
            assertThat(scope.toStructuredJson()).isNull();
        }
    }
}
