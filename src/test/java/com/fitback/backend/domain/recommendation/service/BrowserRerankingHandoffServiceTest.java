package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.product.service.CandidateTokenService;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.recommendation.dto.BrowserRerankingHandoff;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrowserRerankingHandoffServiceTest {

    @Mock
    private CandidateTokenService candidateTokenService;

    private BrowserRerankingHandoffService service;

    @BeforeEach
    void setUp() {
        service = new BrowserRerankingHandoffService(candidateTokenService);
    }

    @Test
    void calculatesZeroPartialAndFullTagSimilarity() {
        List<TagInput> tags = List.of(
                new TagInput(1L, "Cotton", TagType.MATERIAL),
                new TagInput(2L, "Blue", TagType.COLOR),
                new TagInput(3L, "Pleat", TagType.DETAIL),
                new TagInput(4L, "A-line", TagType.SILHOUETTE)
        );

        assertThat(BrowserRerankingHandoffService.tagSimilarity(
                tags,
                candidate("black nylon shirt", "tops/shirts")
        )).isEqualByComparingTo("0.00");
        assertThat(BrowserRerankingHandoffService.tagSimilarity(
                tags,
                candidate("blue cotton shirt", "tops/shirts")
        )).isEqualByComparingTo("0.50");
        assertThat(BrowserRerankingHandoffService.tagSimilarity(
                tags,
                candidate("blue cotton pleat a-line dress", "tops/shirts")
        )).isEqualByComparingTo("1.00");
    }

    @Test
    void excludesStyleAndCustomTagsFromSimilarity() {
        List<TagInput> tags = List.of(
                new TagInput(1L, "Minimal", TagType.STYLE),
                new TagInput(2L, "Cotton", TagType.MATERIAL)
        );

        assertThat(BrowserRerankingHandoffService.tagSimilarity(
                tags,
                candidate("minimal cotton shirt", "tops/shirts")
        )).isEqualByComparingTo("1.00");
    }

    @Test
    void returnsOneWhenNoEligibleConfirmedTagsExist() {
        assertThat(BrowserRerankingHandoffService.tagSimilarity(
                List.of(new TagInput(1L, "Minimal", TagType.STYLE)),
                candidate("plain shirt", "tops/shirts")
        )).isEqualByComparingTo("1.00");
    }

    @Test
    void normalizesCaseAcrossTagAndCandidateText() {
        assertThat(BrowserRerankingHandoffService.tagSimilarity(
                List.of(new TagInput(1L, "CoTtOn", TagType.MATERIAL)),
                candidate("COTTON Shirt", "TOPS/SHIRTS")
        )).isEqualByComparingTo("1.00");
    }

    @Test
    void exposesOnlyOpaqueCandidateIdImageAndTagSimilarity() {
        ExternalProductCandidate candidate = candidate("blue cotton shirt", "tops/shirts");
        when(candidateTokenService.issue(7L, candidate.providerRef())).thenReturn("opaque-token");

        BrowserRerankingHandoff handoff = service.create(
                7L,
                ProductCategory.TOP,
                List.of(new TagInput(1L, "Cotton", TagType.MATERIAL)),
                List.of(candidate)
        );

        assertThat(handoff.category()).isEqualTo(ProductCategory.TOP);
        assertThat(handoff.candidates()).singleElement().satisfies(item -> {
            assertThat(item.candidateId()).isEqualTo("opaque-token");
            assertThat(item.imageUrl()).isEqualTo(candidate.imageUrl());
            assertThat(item.tagSimilarity()).isEqualByComparingTo("1.00");
        });
    }

    @Test
    void limitsBrowserPoolToThirtyCandidates() {
        List<ExternalProductCandidate> candidates = java.util.stream.IntStream.range(0, 35)
                .mapToObj(id -> candidate("shirt " + id, "tops/shirts"))
                .toList();
        when(candidateTokenService.issue(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> "opaque-token-" + invocation.getArgument(1, ProviderProductRef.class)
                        .externalProductId());

        BrowserRerankingHandoff handoff = service.create(
                7L,
                ProductCategory.TOP,
                List.of(),
                candidates
        );

        assertThat(handoff.candidates()).hasSize(30);
    }

    private static ExternalProductCandidate candidate(String name, String categoryPath) {
        return new ExternalProductCandidate(
                ProviderProductRef.stable("fixture", "1", null, "store"),
                name,
                null,
                categoryPath,
                null,
                URI.create("https://example.com/product.jpg"),
                null,
                Instant.parse("2026-08-01T00:00:00Z")
        );
    }
}
