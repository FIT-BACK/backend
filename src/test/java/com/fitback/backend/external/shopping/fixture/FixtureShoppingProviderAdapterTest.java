package com.fitback.backend.external.shopping.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderPriceEvidence;
import com.fitback.backend.domain.product.service.model.ReferenceProductCandidate;
import com.fitback.backend.domain.product.service.model.ReferenceProductDiscoveryQuery;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FixtureShoppingProviderAdapterTest {

    private static final URI IMAGE_URI = URI.create("https://fitback.example/analysis/report.jpg");

    private final FixtureShoppingProviderAdapter adapter = new FixtureShoppingProviderAdapter();

    @Test
    void exposesProviderCapabilitiesWithoutExternalClient() {
        assertThat(adapter.capabilities().provider()).isEqualTo("fixture");
        assertThat(adapter.capabilities().supportsImageSearch()).isTrue();
        assertThat(adapter.capabilities().supportsKeywordSearch()).isTrue();
        assertThat(adapter.capabilities().supportsLookup()).isTrue();
        assertThat(adapter.capabilities().canPersistResult()).isTrue();
        assertThat(adapter.capabilities().requiresLiveLookup()).isFalse();
        assertThat(adapter.capabilities().wishlistSupported()).isTrue();
    }

    @Test
    void discoversStableAndUnstableReferenceCandidatesWithoutGuessingNullableFields() {
        List<ReferenceProductCandidate> candidates = adapter.discover(
                new ReferenceProductDiscoveryQuery(IMAGE_URI, List.of("minimal", "minimal"))
        );

        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst().providerRef().stable()).isTrue();
        assertThat(candidates.getFirst().regularPrice().currency()).isEqualTo("KRW");
        assertThat(candidates.getFirst().currentPrice().amount()).isEqualByComparingTo("80000.00");

        ReferenceProductCandidate unstable = candidates.get(1);
        assertThat(unstable.providerRef().stable()).isFalse();
        assertThat(unstable.providerRef().externalProductId()).isNull();
        assertThat(unstable.brand()).isNull();
        assertThat(unstable.regularPrice()).isNull();
        assertThat(unstable.currentPrice()).isNull();
        assertThat(unstable.imageUrl()).isNull();
    }

    @Test
    void searchesPriceMissingDifferentCurrencyAndUnavailableCandidates() {
        ProductSearchResult result = adapter.search(new ProductSearchQuery("Fixture", null, null, 20));

        assertThat(result.items()).hasSize(4);
        assertThat(result.hasNext()).isFalse();

        ExternalProductCandidate unstable = findByName(result.items(), "Fixture Unstable Look");
        assertThat(unstable.providerRef().stable()).isFalse();
        assertThat(unstable.brand()).isNull();
        assertThat(unstable.categoryPath()).isNull();
        assertThat(unstable.offer()).isNull();

        ExternalProductCandidate usd = findByName(result.items(), "Fixture USD Bag");
        assertThat(usd.offer().currentPrice().currency()).isEqualTo("USD");

        ExternalProductCandidate unavailable = findByName(result.items(), "Fixture Soldout Shoes");
        assertThat(unavailable.offer().availability()).isEqualTo(ProductAvailability.UNAVAILABLE);
    }

    @Test
    void filtersSearchWithProviderCategoryMapping() {
        ProductSearchResult result = adapter.search(
                new ProductSearchQuery("Fixture", ProductCategory.TOP, null, 20)
        );

        assertThat(result.items())
                .extracting(ExternalProductCandidate::name)
                .containsExactly("Fixture Minimal Shirt");
    }

    @Test
    void verifiesKnownSourceUrlAndLooksUpStableReference() {
        ReferenceProductCandidate candidate = adapter.discover(
                new ReferenceProductDiscoveryQuery(IMAGE_URI, List.of())
        ).getFirst();

        Optional<ProviderPriceEvidence> evidence = adapter.verify(
                candidate.providerRef(),
                candidate.sourceUrl()
        );

        assertThat(evidence).isPresent();
        assertThat(evidence.orElseThrow().regularPrice().amount()).isEqualByComparingTo("100000.00");
        assertThat(evidence.orElseThrow().currentPrice().amount()).isEqualByComparingTo("80000.00");
        assertThat(adapter.lookup(candidate.providerRef())).isPresent();
        assertThat(adapter.verify(candidate.providerRef(), URI.create("https://fixture.example/unknown")))
                .isEmpty();
    }

    @Test
    void emptyScenarioReturnsEmptyPortResults() {
        FixtureShoppingProviderAdapter emptyAdapter = new FixtureShoppingProviderAdapter(
                FixtureScenario.EMPTY_RESULT
        );
        ReferenceProductCandidate candidate = adapter.discover(
                new ReferenceProductDiscoveryQuery(IMAGE_URI, List.of())
        ).getFirst();

        assertThat(emptyAdapter.discover(new ReferenceProductDiscoveryQuery(IMAGE_URI, List.of())))
                .isEmpty();
        assertThat(emptyAdapter.search(new ProductSearchQuery("Fixture", null, null, 20)).items())
                .isEmpty();
        assertThat(emptyAdapter.verify(candidate.providerRef(), candidate.sourceUrl())).isEmpty();
        assertThat(emptyAdapter.lookup(candidate.providerRef())).isEmpty();
    }

    @Test
    void duplicateScenarioPreservesRawDuplicatesForLaterDomainDeduplication() {
        FixtureShoppingProviderAdapter duplicateAdapter = new FixtureShoppingProviderAdapter(
                FixtureScenario.DUPLICATE_RESULT
        );

        List<ReferenceProductCandidate> discovered = duplicateAdapter.discover(
                new ReferenceProductDiscoveryQuery(IMAGE_URI, List.of())
        );
        List<ExternalProductCandidate> searched = duplicateAdapter.search(
                new ProductSearchQuery("Fixture", null, null, 20)
        ).items();

        assertThat(discovered).hasSize(2);
        assertThat(discovered.getFirst()).isEqualTo(discovered.getLast());
        assertThat(searched).hasSize(2);
        assertThat(searched.getFirst()).isEqualTo(searched.getLast());
    }

    @Test
    void categoryMapperReturnsKnownCategoryOtherAndEmptyWithoutGuessingMissingPath() {
        FixtureProductCategoryMapper mapper = new FixtureProductCategoryMapper();

        assertThat(mapper.map("fixture", "tops/shirts")).contains(ProductCategory.TOP);
        assertThat(mapper.map("fixture", "unmapped-fashion")).contains(ProductCategory.OTHER);
        assertThat(mapper.map("fixture", null)).isEmpty();
        assertThat(mapper.map("another-provider", "tops/shirts")).isEmpty();
    }

    @Test
    void keepsProviderScoreSeparateFromInternalSimilarityScore() {
        ExternalProductCandidate candidate = adapter.search(
                new ProductSearchQuery("Minimal Shirt", null, null, 20)
        ).items().getFirst();

        assertThat(candidate.providerScore()).isEqualByComparingTo("0.91");
        assertThat(Arrays.stream(ExternalProductCandidate.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("similarityScore");
    }

    @ParameterizedTest
    @MethodSource("failureScenarios")
    void mapsProviderFailureScenariosToNeutralDomainException(
            FixtureScenario scenario,
            ProductProviderFailure expectedFailure
    ) {
        FixtureShoppingProviderAdapter failingAdapter = new FixtureShoppingProviderAdapter(scenario);

        assertThatThrownBy(() -> failingAdapter.search(
                new ProductSearchQuery("Fixture", null, null, 20)
        )).isInstanceOfSatisfying(ProductProviderException.class, exception -> {
            assertThat(exception.getProvider()).isEqualTo("fixture");
            assertThat(exception.getFailure()).isEqualTo(expectedFailure);
            assertThat(exception.getMessage()).doesNotContain("http", "token", "key");
        });
    }

    private static Stream<Arguments> failureScenarios() {
        return Stream.of(
                Arguments.of(FixtureScenario.TIMEOUT, ProductProviderFailure.TIMEOUT),
                Arguments.of(FixtureScenario.RATE_LIMITED, ProductProviderFailure.RATE_LIMITED),
                Arguments.of(FixtureScenario.QUOTA_EXCEEDED, ProductProviderFailure.QUOTA_EXCEEDED),
                Arguments.of(
                        FixtureScenario.AUTHENTICATION_FAILED,
                        ProductProviderFailure.AUTHENTICATION_FAILED
                ),
                Arguments.of(FixtureScenario.UNAVAILABLE, ProductProviderFailure.UNAVAILABLE),
                Arguments.of(FixtureScenario.MALFORMED_RESPONSE, ProductProviderFailure.MALFORMED_RESPONSE)
        );
    }

    private static ExternalProductCandidate findByName(
            List<ExternalProductCandidate> candidates,
            String name
    ) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
