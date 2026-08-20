package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.recommendation.service.RecommendationTagMatcher.Match;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationTagMatcherTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-20T00:00:00Z");

    private final RecommendationRetrievalQueryPlanner queryPlanner =
            new RecommendationRetrievalQueryPlanner();

    @Test
    void reusesCuratedAliasesAcrossNameBrandAndCategoryPath() {
        Match match = RecommendationTagMatcher.match(
                List.of(
                        tag(1, "레귤러핏", TagType.SILHOUETTE),
                        tag(2, "베이지", TagType.COLOR),
                        tag(3, "라운드넥", TagType.DETAIL),
                        tag(4, "니트", TagType.MATERIAL),
                        tag(5, "캐주얼", TagType.STYLE)
                ),
                candidate("regular-fit shirt", "Beige Brand", "tops/crewneck/knit"),
                queryPlanner
        );

        assertThat(match).isEqualTo(new Match(4, 4));
    }

    @Test
    void fallsBackToCanonicalNameWhenAliasIsUnavailable() {
        Match match = RecommendationTagMatcher.match(
                List.of(tag(1, "턱", TagType.DETAIL)),
                candidate("턱 디테일 블라우스", null, "tops/blouses"),
                queryPlanner
        );

        assertThat(match).isEqualTo(new Match(1, 1));
    }

    @Test
    void doesNotIntroduceFuzzyMatchingForCuratedAliases() {
        Match match = RecommendationTagMatcher.match(
                List.of(
                        tag(1, "지퍼", TagType.DETAIL),
                        tag(2, "미디기장", TagType.SILHOUETTE)
                ),
                candidate("slip mini dress", null, "dresses"),
                queryPlanner
        );

        assertThat(match).isEqualTo(new Match(0, 2));
    }

    private static TagInput tag(long id, String name, TagType type) {
        return new TagInput(id, name, type);
    }

    private static ExternalProductCandidate candidate(
            String name,
            String brand,
            String categoryPath
    ) {
        return new ExternalProductCandidate(
                ProviderProductRef.stable("fixture", name, null, "store"),
                name,
                brand,
                categoryPath,
                null,
                URI.create("https://example.com/product.jpg"),
                null,
                OBSERVED_AT
        );
    }
}
