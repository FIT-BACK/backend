package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisionCandidateSelectorTest {

    @Test
    void selectsCandidatesRoundRobinBySearchResultRank() {
        VisionCandidateSelector selector = new VisionCandidateSelector(6);
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(2), candidate(3)),
                List.of(candidate(4), candidate(5), candidate(6)),
                List.of(candidate(7), candidate(8), candidate(9))
        );

        VisionCandidateSelector.SelectionResult result = selector.select(batches);

        assertThat(result.candidates())
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "4", "7", "2", "5", "8");
        assertThat(result.unsupportedReferenceSkipped()).isFalse();
    }

    @Test
    void skipsInvalidAndDuplicateCandidatesUntilLimitIsReached() {
        VisionCandidateSelector selector = new VisionCandidateSelector(3);
        ExternalProductCandidate missingImage = candidate(1, false);
        ExternalProductCandidate sameProductWithImage = candidate(1);
        ExternalProductCandidate duplicate = candidate(2);
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(missingImage, candidate(2), candidate(3)),
                List.of(sameProductWithImage, duplicate, candidate(4))
        );

        VisionCandidateSelector.SelectionResult result = selector.select(batches);

        assertThat(result.candidates())
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "2", "3");
    }

    @Test
    void reportsSkippedUnsupportedReferenceEvenWhenImageIsMissing() {
        VisionCandidateSelector selector = new VisionCandidateSelector(2);
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(unstableCandidate(), candidate(1))
        );

        VisionCandidateSelector.SelectionResult result = selector.select(batches);

        assertThat(result.candidates()).containsExactly(candidate(1));
        assertThat(result.unsupportedReferenceSkipped()).isTrue();
    }

    @Test
    void returnsSameImmutableResultForSameInput() {
        VisionCandidateSelector selector = new VisionCandidateSelector(10);
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(2)),
                List.of(candidate(3))
        );

        VisionCandidateSelector.SelectionResult first = selector.select(batches);
        VisionCandidateSelector.SelectionResult second = selector.select(batches);

        assertThat(first).isEqualTo(second);
        assertThat(first.candidates())
                .containsExactly(candidate(1), candidate(3), candidate(2));
        assertThatThrownBy(() -> first.candidates().add(candidate(4)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonPositiveCandidateLimit() {
        assertThatThrownBy(() -> new VisionCandidateSelector(0))
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
                null,
                null,
                Instant.parse("2026-08-09T00:00:00Z")
        );
    }
}
