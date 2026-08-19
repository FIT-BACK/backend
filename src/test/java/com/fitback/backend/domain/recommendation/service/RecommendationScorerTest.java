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

    // RecommendationRetrievalQueryPlanner의 큐레이션된 한국어→영어 별칭 테이블을
    // 그대로 재사용하므로, 픽스처는 실제 DB 시드(V25__seed_tag_master_taxonomy.sql)의
    // 한국어 태그명을 써야 aliasFor()가 실제로 별칭을 찾아 영어 상품 텍스트와 비교한다.
    private final RecommendationScorer scorer =
            new RecommendationScorer(new RecommendationRetrievalQueryPlanner());

    @Test
    void returnsOneHundredWhenAllAttributeTagsMatch() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "오버사이즈", TagType.SILHOUETTE),
                        tag(20L, "린넨", TagType.MATERIAL),
                        tag(30L, "단추", TagType.DETAIL),
                        tag(40L, "네이비", TagType.COLOR)
                ),
                new BigDecimal("70"),
                candidate("Oversized Shirt", "Navy Brand", "linen/button", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("79.00");
        assertThat(score.reasonCodes())
                .containsExactly("FULL_ATTRIBUTE_MATCH", "HIGH_SIMILARITY");
    }

    @Test
    void calculatesPartialMatchRatioAndRoundsHalfUp() {
        RecommendationScorer.Score halfMatched = scorer.score(
                List.of(
                        tag(10L, "오버사이즈", TagType.SILHOUETTE),
                        tag(20L, "린넨", TagType.MATERIAL),
                        tag(30L, "단추", TagType.DETAIL),
                        tag(40L, "네이비", TagType.COLOR)
                ),
                new BigDecimal("70"),
                candidate("Oversized Shirt", null, "linen", null)
        );
        RecommendationScorer.Score twoOfThreeMatched = scorer.score(
                List.of(
                        tag(10L, "오버사이즈", TagType.SILHOUETTE),
                        tag(20L, "린넨", TagType.MATERIAL),
                        tag(30L, "단추", TagType.DETAIL)
                ),
                new BigDecimal("70"),
                candidate("Oversized Shirt", null, "linen", null)
        );

        // 후보 텍스트("Oversized Shirt"+"linen")에 SILHOUETTE(오버사이즈→oversized)와
        // MATERIAL(린넨→linen)이 매칭되고 COLOR는 안 맞음 — 매칭된 두 개 모두 가중치 1이라
        // 2/9 비율이 적용돼(COLOR 가중치 도입 전 2/4=50%였던 것과 달리) 64.00이 아닌
        // 55.67이 되는 게 올바른 값이다.
        assertThat(halfMatched.similarityScore()).isEqualByComparingTo("55.67");
        assertThat(halfMatched.reasonCodes()).containsExactly("PARTIAL_ATTRIBUTE_MATCH");
        assertThat(twoOfThreeMatched.similarityScore()).isEqualByComparingTo("69.00");
        assertThat(twoOfThreeMatched.reasonCodes())
                .containsExactly("PARTIAL_ATTRIBUTE_MATCH");
    }

    @Test
    void addsHighSimilarityToPartialMatchAtEightyPercent() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "오버사이즈", TagType.SILHOUETTE),
                        tag(20L, "린넨", TagType.MATERIAL),
                        tag(30L, "단추", TagType.DETAIL),
                        tag(40L, "네이비", TagType.COLOR),
                        tag(50L, "지퍼", TagType.DETAIL)
                ),
                new BigDecimal("70"),
                candidate("Oversized Shirt", "Navy Brand", "linen/button", null)
        );

        // COLOR(네이비→navy)까지 매칭돼서 가중 합(9/10)이 예전 단순 개수 비율(4/5=80%)보다 더
        // 높게 나옴 — 76.00이 COLOR 가중치 도입 이후의 올바른 값이다.
        assertThat(score.similarityScore()).isEqualByComparingTo("76.00");
        assertThat(score.reasonCodes())
                .containsExactly("HIGH_SIMILARITY", "PARTIAL_ATTRIBUTE_MATCH");
    }

    @Test
    void colorMatchOutranksAllOtherAttributesMismatched() {
        // 실사용 피드백: 색상이 안 맞으면 다른 속성이 다 맞아도 "안 맞는 옷"으로 느껴짐.
        // COLOR 하나만 맞는 후보가, COLOR만 빼고 나머지 세 속성이 전부 맞는 후보보다
        // 항상 더 높은 점수를 받아야 한다(Fashion-CLIP 연동 전까지는 이 tagMatchScore가
        // 사실상 유일한 순위 결정 요인이므로, 이 성질이 곧 "색상 맞는 게 먼저 나온다"는
        // 뜻이 된다).
        List<TagInput> tags = List.of(
                tag(10L, "오버사이즈", TagType.SILHOUETTE),
                tag(20L, "린넨", TagType.MATERIAL),
                tag(30L, "단추", TagType.DETAIL),
                tag(40L, "네이비", TagType.COLOR)
        );

        RecommendationScorer.Score colorOnlyMatch = scorer.score(
                tags,
                new BigDecimal("70"),
                candidate("Wide Coat", "Other Brand", "outer/coat navy", null)
        );
        RecommendationScorer.Score everythingButColorMatch = scorer.score(
                tags,
                new BigDecimal("70"),
                candidate("Oversized Shirt", "Other Brand", "linen/button", null)
        );

        assertThat(colorOnlyMatch.similarityScore())
                .isGreaterThan(everythingButColorMatch.similarityScore());
    }

    @Test
    void returnsNoAttributeMatchWhenNoAttributeTagsMatch() {
        RecommendationScorer.Score score = scorer.score(
                List.of(
                        tag(10L, "슬림핏", TagType.SILHOUETTE),
                        tag(20L, "가죽", TagType.MATERIAL),
                        tag(30L, "지퍼", TagType.DETAIL),
                        tag(40L, "브라운", TagType.COLOR)
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
                List.of(tag(10L, "미니멀", TagType.STYLE)),
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
                        tag(10L, "미니멀", TagType.STYLE),
                        tag(20L, "린넨", TagType.MATERIAL),
                        tag(30L, "단추", TagType.DETAIL)
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
                        tag(10L, "오버사이즈", TagType.SILHOUETTE),
                        tag(20L, "린넨", TagType.MATERIAL),
                        tag(30L, "단추", TagType.DETAIL)
                ),
                new BigDecimal("71.11"),
                candidate("Oversized Shirt", null, "linen", null)
        );

        assertThat(score.similarityScore()).isEqualByComparingTo("69.78");
    }

    @Test
    void validatesTemporaryImageSimilarityScoreRange() {
        List<TagInput> tags = List.of(tag(10L, "오버사이즈", TagType.SILHOUETTE));
        ExternalProductCandidate candidate = candidate(
                "Oversized Shirt",
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
