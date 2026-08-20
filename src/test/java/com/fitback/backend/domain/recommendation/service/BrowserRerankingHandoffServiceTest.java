package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import com.fitback.backend.domain.product.service.CandidateTokenService;
import com.fitback.backend.domain.product.service.ProductCandidateMapper;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.Money;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductOffer;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.recommendation.dto.BrowserRerankingHandoff;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrowserRerankingHandoffServiceTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @Mock
    private CandidateTokenService candidateTokenService;

    @Mock
    private ProductCandidateMapper candidateMapper;

    private BrowserRerankingHandoffService service;

    @BeforeEach
    void setUp() {
        service = new BrowserRerankingHandoffService(
                candidateTokenService,
                candidateMapper,
                new RecommendationRetrievalQueryPlanner()
        );
    }

    @Test
    void calculatesZeroPartialAndFullTagSimilarity() {
        List<TagInput> tags = List.of(
                new TagInput(1L, "Cotton", TagType.MATERIAL),
                new TagInput(2L, "Blue", TagType.COLOR),
                new TagInput(3L, "Pleat", TagType.DETAIL),
                new TagInput(4L, "A-line", TagType.SILHOUETTE)
        );

        assertThat(service.tagSimilarity(
                tags,
                candidate("black nylon shirt", null)
        )).isEqualByComparingTo("0.00");
        assertThat(service.tagSimilarity(
                tags,
                candidate("blue cotton shirt", null)
        )).isEqualByComparingTo("0.50");
        assertThat(service.tagSimilarity(
                tags,
                candidate("blue cotton pleat a-line dress", null)
        )).isEqualByComparingTo("1.00");
    }

    @Test
    void matchesKoreanCanonicalTagsWithRetrievalAliases() {
        List<TagInput> tags = List.of(
                new TagInput(1L, "레귤러핏", TagType.SILHOUETTE),
                new TagInput(2L, "베이지", TagType.COLOR),
                new TagInput(3L, "라운드넥", TagType.DETAIL),
                new TagInput(4L, "니트", TagType.MATERIAL),
                new TagInput(5L, "캐주얼", TagType.STYLE)
        );

        assertThat(service.tagSimilarity(
                tags,
                candidate("regular-fit beige crewneck knit shirt", null)
        )).isEqualByComparingTo("1.00");
    }

    @ParameterizedTest
    @CsvSource({
            "regular-fit beige crewneck knit shirt, 1.00",
            "regular-fit beige crewneck shirt, 0.75",
            "regular-fit beige shirt, 0.50",
            "regular-fit shirt, 0.25",
            "plain shirt, 0.00"
    })
    void keepsUnweightedPartialMatchRatio(String productName, String expectedSimilarity) {
        List<TagInput> tags = List.of(
                new TagInput(1L, "레귤러핏", TagType.SILHOUETTE),
                new TagInput(2L, "베이지", TagType.COLOR),
                new TagInput(3L, "라운드넥", TagType.DETAIL),
                new TagInput(4L, "니트", TagType.MATERIAL),
                new TagInput(5L, "캐주얼", TagType.STYLE)
        );

        assertThat(service.tagSimilarity(tags, candidate(productName, null)))
                .isEqualByComparingTo(expectedSimilarity);
    }

    @Test
    void excludesStyleAndCustomTagsFromSimilarity() {
        List<TagInput> tags = List.of(
                new TagInput(1L, "Minimal", TagType.STYLE),
                new TagInput(2L, "Cotton", TagType.MATERIAL)
        );

        assertThat(service.tagSimilarity(
                tags,
                candidate("minimal cotton shirt", null)
        )).isEqualByComparingTo("1.00");
    }

    @Test
    void preservesOneForZeroEligibleTags() {
        assertThat(service.tagSimilarity(
                List.of(new TagInput(1L, "캐주얼", TagType.STYLE)),
                candidate("unrelated shirt", null)
        )).isEqualByComparingTo("1.00");
    }

    @Test
    void mapsExternalCandidateSnapshotAndReusesProductPriceResponse() {
        ProductOffer offer = offer(
                "Fixture Store",
                URI.create("https://fixture.example/products/top-001"),
                new Money(new BigDecimal("80000.00"), "KRW")
        );
        ExternalProductCandidate candidate = candidate("Fixture Minimal Shirt", offer);
        ProductPriceResponse price = new ProductPriceResponse(
                new BigDecimal("80000.00"),
                "KRW",
                ProductPriceResponse.Type.CURRENT,
                OBSERVED_AT
        );
        when(candidateTokenService.issue(7L, candidate.providerRef())).thenReturn("opaque-token");
        when(candidateMapper.price(offer)).thenReturn(price);

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
            assertThat(item.tagSimilarity()).isEqualByComparingTo("0.00");
            assertThat(item.name()).isEqualTo("Fixture Minimal Shirt");
            assertThat(item.sellerName()).isEqualTo("Fixture Store");
            assertThat(item.price()).isSameAs(price);
            assertThat(item.purchaseUrl()).isEqualTo(offer.purchaseUrl());
        });
    }

    @Test
    void preservesNullSellerPurchaseUrlAndPriceWithoutDefaults() {
        ProductOffer offer = offer(null, null, null);
        ExternalProductCandidate candidate = candidate("Fixture Unpriced Shirt", offer);
        when(candidateTokenService.issue(7L, candidate.providerRef())).thenReturn("opaque-token");

        BrowserRerankingHandoff handoff = service.create(
                7L,
                ProductCategory.TOP,
                List.of(),
                List.of(candidate)
        );

        assertThat(handoff.candidates()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("Fixture Unpriced Shirt");
            assertThat(item.sellerName()).isNull();
            assertThat(item.price()).isNull();
            assertThat(item.purchaseUrl()).isNull();
        });
    }

    @Test
    void limitsBrowserPoolToThirtyCandidates() {
        List<ExternalProductCandidate> candidates = java.util.stream.IntStream.range(0, 35)
                .mapToObj(id -> candidate("shirt " + id, null))
                .toList();
        when(candidateTokenService.issue(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(ProviderProductRef.class)
        )).thenAnswer(invocation -> "opaque-token-"
                + invocation.getArgument(1, ProviderProductRef.class).externalProductId());

        BrowserRerankingHandoff handoff = service.create(
                7L,
                ProductCategory.TOP,
                List.of(),
                candidates
        );

        assertThat(handoff.candidates()).hasSize(30);
    }

    private static ExternalProductCandidate candidate(String name, ProductOffer offer) {
        return new ExternalProductCandidate(
                ProviderProductRef.stable("fixture", name, null, "store"),
                name,
                null,
                "tops/shirts",
                offer,
                URI.create("https://example.com/product.jpg"),
                null,
                OBSERVED_AT
        );
    }

    private static ProductOffer offer(String seller, URI purchaseUrl, Money currentPrice) {
        return new ProductOffer(
                null,
                currentPrice,
                null,
                ProductAvailability.UNKNOWN,
                seller,
                purchaseUrl,
                null,
                OBSERVED_AT
        );
    }
}
