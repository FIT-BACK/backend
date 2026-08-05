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
    void returnsOneHundredWhenAllAttributeTagsMatch() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.SILHOUETTE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL),
                        tag(40L, "navy", TagType.COLOR)
                ),
                candidate("Minimal Shirt", "Navy Brand", "linen/button", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("100.00");
        assertThat(score.reasonCodes()).containsExactly("HIGH_SIMILARITY", "TAG_MATCH");
    }

    @Test
    void calculatesPartialMatchRatioAndRoundsHalfUp() {
        RecommendationScorer.Score halfMatched = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.SILHOUETTE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL),
                        tag(40L, "navy", TagType.COLOR)
                ),
                candidate("Minimal Shirt", null, "linen", null)
        );
        RecommendationScorer.Score twoOfThreeMatched = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.SILHOUETTE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL)
                ),
                candidate("Minimal Shirt", null, "linen", null)
        );

        assertThat(halfMatched.similarityScore()).isEqualByComparingTo("50.00");
        assertThat(halfMatched.reasonCodes()).containsExactly("TAG_MATCH");
        assertThat(twoOfThreeMatched.similarityScore()).isEqualByComparingTo("66.67");
        assertThat(twoOfThreeMatched.reasonCodes()).containsExactly("TAG_MATCH");
    }

    @Test
    void returnsZeroAndEmptyReasonsWhenNoAttributeTagsMatch() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "wide", TagType.SILHOUETTE),
                        tag(20L, "wool", TagType.MATERIAL),
                        tag(30L, "zipper", TagType.DETAIL),
                        tag(40L, "red", TagType.COLOR)
                ),
                candidate("Minimal Shirt", "Fixture", "tops/shirts", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("0.00");
        assertThat(score.reasonCodes()).isEmpty();
    }

    @Test
    void returnsOneHundredWhenThereAreNoAttributeTags() {
        RecommendationScorer.Score score = scorer.score(
                List.of(tag(10L, "minimal", TagType.STYLE)),
                candidate("Unrelated Product", null, null, null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("100.00");
        assertThat(score.reasonCodes()).containsExactly("HIGH_SIMILARITY");
    }

    @Test
    void excludesStyleAndIgnoresProviderScore() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.STYLE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL)
                ),
                candidate(
                        "Minimal Shirt",
                        null,
                        "linen",
                        new BigDecimal("0.01")
                )
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("50.00");
        assertThat(score.reasonCodes()).containsExactly("TAG_MATCH");
    }

    private static TagInput tag(Long id, String name, TagType tagType) {
        return new TagInput(id, name, tagType);
    }

    private static ExternalProductCandidate candidate(
            String name,
            String brand,
            String categoryPath,
            BigDecimal providerScore
    ) {
        return new ExternalProductCandidate(
                ProviderProductRef.stable("fixture", name, null, "store"),
                name,
                brand,
                categoryPath,
                null,
                null,
                providerScore,
                Instant.parse("2026-07-25T00:00:00Z")
        );
    }
}
