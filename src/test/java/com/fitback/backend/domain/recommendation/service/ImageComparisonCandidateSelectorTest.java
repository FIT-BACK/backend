package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageComparisonCandidateSelectorTest {

    @Test
    void skipsInvalidAndDuplicateCandidatesUntilLimitIsReached() {
        ImageComparisonCandidateSelector selector = selector(3);
        ExternalProductCandidate missingImage = candidate(1, false);
        ExternalProductCandidate sameProductWithImage = candidate(1);
        ExternalProductCandidate duplicate = candidate(2);
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(
                        missingImage,
                        sameProductWithImage,
                        candidate(2),
                        duplicate,
                        candidate(3),
                        candidate(4)
                )
        );

        ImageComparisonCandidateSelector.SelectionResult result = selector.select(batches);

        assertThat(result.candidates())
                .extracting(candidate -> candidate.providerRef().externalProductId())
                .containsExactly("1", "2", "3");
    }

    @Test
    void reportsSkippedUnsupportedReferenceEvenWhenImageIsMissing() {
        ImageComparisonCandidateSelector selector = selector(2);
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(unstableCandidate(), candidate(1))
        );

        ImageComparisonCandidateSelector.SelectionResult result = selector.select(batches);

        assertThat(result.candidates()).containsExactly(candidate(1));
        assertThat(result.unsupportedReferenceSkipped()).isTrue();
    }

    @Test
    void returnsSameImmutableResultForSameInput() {
        ImageComparisonCandidateSelector selector = selector(10);
        List<List<ExternalProductCandidate>> batches = List.of(
                List.of(candidate(1), candidate(3), candidate(2))
        );

        ImageComparisonCandidateSelector.SelectionResult first = selector.select(batches);
        ImageComparisonCandidateSelector.SelectionResult second = selector.select(batches);

        assertThat(first).isEqualTo(second);
        assertThat(first.candidates())
                .containsExactly(candidate(1), candidate(3), candidate(2));
        assertThatThrownBy(() -> first.candidates().add(candidate(4)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonPositiveCandidateLimit() {
        assertThatThrownBy(() -> selector(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidateLimit must be positive");
    }

    private static ImageComparisonCandidateSelector selector(int candidateLimit) {
        return new ImageComparisonCandidateSelector(
                candidateBatches -> candidateBatches.stream()
                        .flatMap(List::stream)
                        .toList(),
                candidateLimit
        );
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
