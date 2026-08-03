package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationScorerTest {

    private final RecommendationScorer scorer = new RecommendationScorer();

    @Test
    void normalizesProviderScoreAndSortsReasonCodes() {
        ExternalProductCandidate candidate = candidate(
                "Minimal Linen Shirt",
                new BigDecimal("0.91234")
        );

        RecommendationScorer.Score score = scorer.score(
                List.of(new TagInput(10L, "minimal", TagType.DETAIL)),
                List.of(),
                candidate
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("91.23");
        assertThat(score.reasonCodes()).containsExactly("HIGH_SIMILARITY", "TAG_MATCH");
    }

    @Test
    void usesDeterministicTagFallbackWhenProviderScoreIsMissing() {
        RecommendationScorer.Score matched = scorer.score(
                List.of(new TagInput(10L, "shirt", TagType.DETAIL)),
                List.of(),
                candidate("Minimal Shirt", null)
        );
        RecommendationScorer.Score unmatched = scorer.score(
                List.of(new TagInput(20L, "dress", TagType.SILHOUETTE)),
                List.of(),
                candidate("Minimal Shirt", null)
        );

        assertThat(matched.similarityScore()).isEqualByComparingTo("70.00");
        assertThat(matched.reasonCodes()).containsExactly("TAG_MATCH");
        assertThat(unmatched.similarityScore()).isEqualByComparingTo("0.00");
        assertThat(unmatched.reasonCodes()).containsExactly("PROVIDER_SIMILARITY");
    }

    @Test
    void includesCustomTagNamesInExistingMatchingPolicy() {
        RecommendationScorer.Score score = scorer.score(
                List.of(),
                List.of("shirt"),
                candidate("Minimal Shirt", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("70.00");
        assertThat(score.reasonCodes()).containsExactly("TAG_MATCH");
    }

    private static ExternalProductCandidate candidate(String name, BigDecimal providerScore) {
        return new ExternalProductCandidate(
                ProviderProductRef.stable("fixture", name, null, "store"),
                name,
                null,
                "tops/shirts",
                null,
                null,
                providerScore,
                Instant.parse("2026-07-25T00:00:00Z")
        );
    }
}
