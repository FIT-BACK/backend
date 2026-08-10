package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                new BigDecimal("70"),
                candidate("Minimal Shirt", "Navy Brand", "linen/button", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("79.00");
        assertThat(score.reasonCodes())
                .containsExactly("FULL_ATTRIBUTE_MATCH", "HIGH_SIMILARITY");
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
                new BigDecimal("70"),
                candidate("Minimal Shirt", null, "linen", null)
        );
        RecommendationScorer.Score twoOfThreeMatched = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.SILHOUETTE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL)
                ),
                new BigDecimal("70"),
                candidate("Minimal Shirt", null, "linen", null)
        );

        assertThat(halfMatched.similarityScore()).isEqualByComparingTo("64.00");
        assertThat(halfMatched.reasonCodes()).containsExactly("PARTIAL_ATTRIBUTE_MATCH");
        assertThat(twoOfThreeMatched.similarityScore()).isEqualByComparingTo("69.00");
        assertThat(twoOfThreeMatched.reasonCodes())
                .containsExactly("PARTIAL_ATTRIBUTE_MATCH");
    }

    @Test
    void addsHighSimilarityToPartialMatchAtEightyPercent() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.SILHOUETTE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL),
                        tag(40L, "navy", TagType.COLOR),
                        tag(50L, "unmatched", TagType.DETAIL)
                ),
                new BigDecimal("70"),
                candidate("Minimal Shirt", "Navy Brand", "linen/button", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("73.00");
        assertThat(score.reasonCodes())
                .containsExactly("HIGH_SIMILARITY", "PARTIAL_ATTRIBUTE_MATCH");
    }

    @Test
    void returnsNoAttributeMatchWhenNoAttributeTagsMatch() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "wide", TagType.SILHOUETTE),
                        tag(20L, "wool", TagType.MATERIAL),
                        tag(30L, "zipper", TagType.DETAIL),
                        tag(40L, "red", TagType.COLOR)
                ),
                new BigDecimal("70"),
                candidate("Minimal Shirt", "Fixture", "tops/shirts", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("49.00");
        assertThat(score.reasonCodes()).containsExactly("NO_ATTRIBUTE_MATCH");
    }

    @Test
    void returnsOneHundredWhenThereAreNoAttributeTags() {
        RecommendationScorer.Score score = scorer.score(
                List.of(tag(10L, "minimal", TagType.STYLE)),
                new BigDecimal("70"),
                candidate("Unrelated Product", null, null, null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("79.00");
        assertThat(score.reasonCodes()).containsExactly("NO_SCORABLE_TAGS");
    }

    @Test
    void excludesStyleAndIgnoresProviderScore() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.STYLE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL)
                ),
                new BigDecimal("70"),
                candidate(
                        "Minimal Shirt",
                        null,
                        "linen",
                        new BigDecimal("0.01")
                )
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("64.00");
        assertThat(score.reasonCodes()).containsExactly("PARTIAL_ATTRIBUTE_MATCH");
    }

    @Test
    void combinesImageAndTagScoresAndRoundsHalfUp() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "minimal", TagType.SILHOUETTE),
                        tag(20L, "linen", TagType.MATERIAL),
                        tag(30L, "button", TagType.DETAIL)
                ),
                new BigDecimal("71.11"),
                candidate("Minimal Shirt", null, "linen", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("69.78");
    }

    @Test
    void validatesTemporaryImageSimilarityScoreRange() {
        List<TagInput> tags = List.of(tag(10L, "minimal", TagType.SILHOUETTE));
        ExternalProductCandidate candidate = candidate(
                "Minimal Shirt",
                null,
                null,
                null
        );

        assertThatThrownBy(() -> scorer.score(tags, null, candidate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scorer.score(tags, new BigDecimal("-0.01"), candidate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scorer.score(tags, new BigDecimal("100.01"), candidate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(scorer.score(tags, BigDecimal.ZERO, candidate).similarityScore())
                .isEqualByComparingTo("30.00");
        assertThat(scorer.score(tags, new BigDecimal("100"), candidate).similarityScore())
                .isEqualByComparingTo("100.00");
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
