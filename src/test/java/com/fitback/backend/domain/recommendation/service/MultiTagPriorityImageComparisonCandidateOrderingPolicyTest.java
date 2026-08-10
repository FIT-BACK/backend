package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiTagPriorityImageComparisonCandidateOrderingPolicyTest {

    private final MultiTagPriorityImageComparisonCandidateOrderingPolicy policy =
            new MultiTagPriorityImageComparisonCandidateOrderingPolicy();

    @Test
    void prioritizesCandidatesFoundByMultipleTagSearches() {
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(2), candidate(3)),
                List.of(candidate(4), candidate(1), candidate(5)),
                List.of(candidate(2), candidate(6), candidate(1))
        );

        List<ExternalProductCandidate> orderedCandidates = policy.order(batches, 6);

        assertThat(orderedCandidates)
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "2", "4", "6", "3", "5");
    }

    @Test
    void usesOneThirdOfCandidateLimitAsMultiTagPrioritySlots() {
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(9), candidate(2), candidate(3)),
                List.of(candidate(1), candidate(8), candidate(2), candidate(3))
        );

        List<ExternalProductCandidate> orderedCandidates = policy.order(batches, 6);

        assertThat(orderedCandidates)
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .startsWith("1", "2", "9", "8");
    }

    @Test
    void usesBestSearchRankAndProviderReferenceAsStableTieBreakers() {
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(3), candidate(2), candidate(1)),
                List.of(candidate(1), candidate(3), candidate(2))
        );

        List<ExternalProductCandidate> orderedCandidates = policy.order(batches, 9);

        assertThat(orderedCandidates)
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "3", "2");
    }

    @Test
    void doesNotCountDuplicatesWithinOneSearchResultAsMultipleTags() {
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(1), candidate(2)),
                List.of(candidate(3), candidate(4))
        );

        List<ExternalProductCandidate> orderedCandidates = policy.order(batches, 3);

        assertThat(orderedCandidates)
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "3", "1", "4", "2");
    }

    @Test
    void excludesCandidatesWithoutStableReferenceOrImageFromPrioritySlots() {
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1, false), unstableCandidate(), candidate(2)),
                List.of(candidate(1, false), unstableCandidate(), candidate(2))
        );

        List<ExternalProductCandidate> orderedCandidates = policy.order(batches, 6);

        assertThat(orderedCandidates.getFirst()).isEqualTo(candidate(2));
    }

    @Test
    void rejectsNonPositiveCandidateLimit() {
        assertThatThrownBy(() -> policy.order(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidateLimit must be positive");
    }

    private static ExternalProductCandidate candidate(int id) {
        return candidate(id, true);
    }

    private static ExternalProductCandidate candidate(int id, boolean hasImage) {
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
                hasImage ? URI.create("https://example.com/products/" + id + ".jpg") : null,
                null,
                Instant.parse("2026-08-09T00:00:00Z")
        );
    }

    private static ExternalProductCandidate unstableCandidate() {
        return new ExternalProductCandidate(
                ProviderProductRef.unstable("fixture"),
                "Unstable Product",
                null,
                "tops/shirts",
                null,
                URI.create("https://example.com/products/unstable.jpg"),
                null,
                Instant.parse("2026-08-09T00:00:00Z")
        );
    }
}
