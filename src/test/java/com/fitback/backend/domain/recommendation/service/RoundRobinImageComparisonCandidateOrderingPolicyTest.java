package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoundRobinImageComparisonCandidateOrderingPolicyTest {

    private final RoundRobinImageComparisonCandidateOrderingPolicy policy =
            new RoundRobinImageComparisonCandidateOrderingPolicy();

    @Test
    void ordersCandidatesRoundRobinBySearchResultRank() {
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(2), candidate(3)),
                List.of(candidate(4), candidate(5), candidate(6)),
                List.of(candidate(7), candidate(8), candidate(9))
        );

        List<ExternalProductCandidate> orderedCandidates = policy.order(batches);

        assertThat(orderedCandidates)
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "4", "7", "2", "5", "8", "3", "6", "9");
    }

    @Test
    void keepsOrderingUntilTheLongestSearchResultIsExhausted() {
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(2), candidate(3)),
                List.of(candidate(4)),
                List.of()
        );

        List<ExternalProductCandidate> orderedCandidates = policy.order(batches);

        assertThat(orderedCandidates)
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "4", "2", "3");
    }

    private static ExternalProductCandidate candidate(int id) {
        return new ExternalProductCandidate(
                ProviderProductRef.stable(
                        "fixture",
                        Integer.toString(id),
                        null,
                        "store"
                ),
                "Fixture Product " + id,
                null,
                "tops/shirts",
                null,
                URI.create("https://example.com/products/" + id + ".jpg"),
                null,
                Instant.parse("2026-08-09T00:00:00Z")
        );
    }
}
