package com.fitback.backend.external.shopping.fixture;

import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.Money;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductOffer;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderPriceEvidence;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.model.ReferenceEvidenceType;
import com.fitback.backend.domain.product.service.model.ReferenceProductCandidate;
import com.fitback.backend.domain.product.service.model.ReferenceProductDiscoveryQuery;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.domain.product.service.port.ProductPriceVerificationPort;
import com.fitback.backend.domain.product.service.port.ReferenceProductDiscoveryPort;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public class FixtureShoppingProviderAdapter implements
        ReferenceProductDiscoveryPort,
        ProductPriceVerificationPort,
        ProductCatalogPort {

    public static final String PROVIDER = "fixture";

    private static final Instant OBSERVED_AT = Instant.parse("2026-07-18T03:00:00Z");
    private static final URI STABLE_SOURCE_URL = URI.create("https://fixture.example/products/top-001");
    private static final ProviderProductRef STABLE_PRODUCT_REF = ProviderProductRef.stable(
            PROVIDER,
            "fixture-top-001",
            "beige-m",
            "fixture-store"
    );
    private static final ProviderProductRef UNSTABLE_PRODUCT_REF = ProviderProductRef.unstable(PROVIDER);
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(
            PROVIDER,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            Duration.ofHours(1),
            true
    );

    private final FixtureScenario scenario;
    private final FixtureProductCategoryMapper categoryMapper;

    public FixtureShoppingProviderAdapter() {
        this(FixtureScenario.SUCCESS);
    }

    public FixtureShoppingProviderAdapter(FixtureScenario scenario) {
        this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
        this.categoryMapper = new FixtureProductCategoryMapper();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<ReferenceProductCandidate> discover(ReferenceProductDiscoveryQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        guardFailure();
        if (scenario == FixtureScenario.EMPTY_RESULT) {
            return List.of();
        }

        ReferenceProductCandidate stable = stableReferenceCandidate();
        if (scenario == FixtureScenario.DUPLICATE_RESULT) {
            return List.of(stable, stable);
        }
        return List.of(stable, unstableReferenceCandidate());
    }

    @Override
    public Optional<ProviderPriceEvidence> verify(ProviderProductRef providerRef, URI sourceUrl) {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");
        guardFailure();
        if (scenario == FixtureScenario.EMPTY_RESULT
                || !STABLE_PRODUCT_REF.equals(providerRef)
                || !STABLE_SOURCE_URL.equals(sourceUrl)) {
            return Optional.empty();
        }
        return Optional.of(new ProviderPriceEvidence(
                money("100000.00", "KRW"),
                money("80000.00", "KRW"),
                STABLE_SOURCE_URL,
                OBSERVED_AT
        ));
    }

    @Override
    public ProductSearchResult search(ProductSearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        guardFailure();
        if (scenario == FixtureScenario.EMPTY_RESULT) {
            return new ProductSearchResult(List.of(), null);
        }

        List<ExternalProductCandidate> candidates;
        if (scenario == FixtureScenario.DUPLICATE_RESULT) {
            ExternalProductCandidate stable = stableExternalCandidate();
            candidates = List.of(stable, stable);
        } else {
            candidates = List.of(
                    stableExternalCandidate(),
                    unstableExternalCandidate(),
                    usdExternalCandidate(),
                    unavailableExternalCandidate()
            );
        }

        String keyword = query.keyword().toLowerCase(Locale.ROOT);
        List<ExternalProductCandidate> filtered = candidates.stream()
                .filter(candidate -> candidate.name().toLowerCase(Locale.ROOT).contains(keyword))
                .filter(candidate -> matchesCategory(candidate, query.category()))
                .limit(query.pageSize())
                .toList();
        return new ProductSearchResult(filtered, null);
    }

    @Override
    public Optional<ExternalProductCandidate> lookup(ProviderProductRef providerRef) {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        guardFailure();
        if (scenario == FixtureScenario.EMPTY_RESULT) {
            return Optional.empty();
        }
        return allExternalCandidates().stream()
                .filter(candidate -> candidate.providerRef().equals(providerRef))
                .findFirst();
    }

    private boolean matchesCategory(ExternalProductCandidate candidate, ProductCategory category) {
        if (category == null) {
            return true;
        }
        return categoryMapper.map(PROVIDER, candidate.categoryPath())
                .filter(category::equals)
                .isPresent();
    }

    private void guardFailure() {
        ProductProviderFailure failure = switch (scenario) {
            case TIMEOUT -> ProductProviderFailure.TIMEOUT;
            case RATE_LIMITED -> ProductProviderFailure.RATE_LIMITED;
            case QUOTA_EXCEEDED -> ProductProviderFailure.QUOTA_EXCEEDED;
            case AUTHENTICATION_FAILED -> ProductProviderFailure.AUTHENTICATION_FAILED;
            case UNAVAILABLE -> ProductProviderFailure.UNAVAILABLE;
            case MALFORMED_RESPONSE -> ProductProviderFailure.MALFORMED_RESPONSE;
            case SUCCESS, EMPTY_RESULT, DUPLICATE_RESULT -> null;
        };
        if (failure != null) {
            throw new ProductProviderException(PROVIDER, failure);
        }
    }

    private static List<ExternalProductCandidate> allExternalCandidates() {
        return List.of(
                stableExternalCandidate(),
                unstableExternalCandidate(),
                usdExternalCandidate(),
                unavailableExternalCandidate()
        );
    }

    private static ReferenceProductCandidate stableReferenceCandidate() {
        return new ReferenceProductCandidate(
                STABLE_PRODUCT_REF,
                "Fixture Minimal Shirt",
                "Fixture Brand",
                money("100000.00", "KRW"),
                money("80000.00", "KRW"),
                STABLE_SOURCE_URL,
                URI.create("https://fixture.example/images/top-001.jpg"),
                ReferenceEvidenceType.IMAGE_MATCH,
                new BigDecimal("0.91"),
                OBSERVED_AT
        );
    }

    private static ReferenceProductCandidate unstableReferenceCandidate() {
        return new ReferenceProductCandidate(
                UNSTABLE_PRODUCT_REF,
                "Fixture Unstable Look",
                null,
                null,
                null,
                URI.create("https://fixture.example/discovery/unstable"),
                null,
                ReferenceEvidenceType.VISUAL_SIMILARITY,
                null,
                OBSERVED_AT
        );
    }

    private static ExternalProductCandidate stableExternalCandidate() {
        return new ExternalProductCandidate(
                STABLE_PRODUCT_REF,
                "Fixture Minimal Shirt",
                "Fixture Brand",
                "tops/shirts",
                new ProductOffer(
                        money("100000.00", "KRW"),
                        money("80000.00", "KRW"),
                        money("70000.00", "KRW"),
                        ProductAvailability.AVAILABLE,
                        "Fixture Store",
                        STABLE_SOURCE_URL,
                        null,
                        OBSERVED_AT
                ),
                URI.create("https://fixture.example/images/top-001.jpg"),
                new BigDecimal("0.91"),
                OBSERVED_AT
        );
    }

    private static ExternalProductCandidate unstableExternalCandidate() {
        return new ExternalProductCandidate(
                UNSTABLE_PRODUCT_REF,
                "Fixture Unstable Look",
                null,
                null,
                new ProductOffer(
                        null,
                        null,
                        null,
                        ProductAvailability.UNKNOWN,
                        null,
                        URI.create("https://fixture.example/discovery/unstable"),
                        null,
                        OBSERVED_AT
                ),
                null,
                null,
                OBSERVED_AT
        );
    }

    private static ExternalProductCandidate usdExternalCandidate() {
        return new ExternalProductCandidate(
                ProviderProductRef.stable(PROVIDER, "fixture-bag-usd", null, "fixture-us-store"),
                "Fixture USD Bag",
                null,
                "bags",
                new ProductOffer(
                        null,
                        money("125.00", "USD"),
                        null,
                        ProductAvailability.AVAILABLE,
                        "Fixture US Store",
                        URI.create("https://fixture.example/products/bag-usd"),
                        null,
                        OBSERVED_AT
                ),
                null,
                null,
                OBSERVED_AT
        );
    }

    private static ExternalProductCandidate unavailableExternalCandidate() {
        return new ExternalProductCandidate(
                ProviderProductRef.stable(PROVIDER, "fixture-shoes-soldout", null, "fixture-store"),
                "Fixture Soldout Shoes",
                "Fixture Brand",
                "shoes",
                new ProductOffer(
                        null,
                        money("50000.00", "KRW"),
                        null,
                        ProductAvailability.UNAVAILABLE,
                        "Fixture Store",
                        URI.create("https://fixture.example/products/shoes-soldout"),
                        null,
                        OBSERVED_AT
                ),
                null,
                null,
                OBSERVED_AT
        );
    }

    private static Money money(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }
}
