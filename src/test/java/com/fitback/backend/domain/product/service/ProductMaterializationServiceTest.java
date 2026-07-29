package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.product.dto.ProductReferenceResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductSnapshot;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductMaterializationServiceTest {

    @Test
    void returnsExistingProductWithoutCallingProviderLookupAgain() {
        ProductCatalogPort productCatalogPort = mock(ProductCatalogPort.class);
        CandidateTokenService candidateTokenService = mock(CandidateTokenService.class);
        ProductIdentityHasher identityHasher = mock(ProductIdentityHasher.class);
        ProductCandidateMapper candidateMapper = mock(ProductCandidateMapper.class);
        ProductPersistenceService persistenceService = mock(ProductPersistenceService.class);
        ProviderProductRef providerRef = ProviderProductRef.stable(
                "fixture",
                "product-1",
                "variant-1",
                "merchant-1"
        );
        ProviderCapabilities capabilities = new ProviderCapabilities(
                "fixture",
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
        Product existingProduct = mock(Product.class);

        when(candidateTokenService.verify("candidate-token", 10L)).thenReturn(providerRef);
        when(productCatalogPort.capabilities()).thenReturn(capabilities);
        when(identityHasher.hash(providerRef)).thenReturn("identity-key");
        when(persistenceService.findStableProduct("fixture", "identity-key"))
                .thenReturn(Optional.of(existingProduct));
        when(existingProduct.getId()).thenReturn(42L);
        when(existingProduct.getAvailability()).thenReturn(ProductAvailability.AVAILABLE);

        ProductMaterializationService service = new ProductMaterializationService(
                productCatalogPort,
                candidateTokenService,
                identityHasher,
                candidateMapper,
                persistenceService,
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC)
        );

        ProductReferenceResponse response = service.materialize(10L, "candidate-token");

        assertThat(response).isEqualTo(new ProductReferenceResponse(
                42L,
                false,
                ProductAvailability.AVAILABLE
        ));
        verify(productCatalogPort, never()).lookup(providerRef);
        verifyNoInteractions(candidateMapper);
    }

    @Test
    void materializesRecommendationCandidateAndRefreshesExistingSnapshot() {
        ProductCatalogPort productCatalogPort = mock(ProductCatalogPort.class);
        CandidateTokenService candidateTokenService = mock(CandidateTokenService.class);
        ProductIdentityHasher identityHasher = mock(ProductIdentityHasher.class);
        ProductCandidateMapper candidateMapper = mock(ProductCandidateMapper.class);
        ProductPersistenceService persistenceService = mock(ProductPersistenceService.class);
        ProviderProductRef providerRef = ProviderProductRef.stable(
                "fixture",
                "product-1",
                "variant-1",
                "merchant-1"
        );
        ExternalProductCandidate candidate = new ExternalProductCandidate(
                providerRef,
                "Fixture Product",
                null,
                "tops/shirts",
                null,
                null,
                null,
                Instant.parse("2026-07-24T00:00:00Z")
        );
        ProviderCapabilities capabilities = new ProviderCapabilities(
                "fixture",
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
        ProductSnapshot snapshot = mock(ProductSnapshot.class);
        Product existingProduct = mock(Product.class);
        when(productCatalogPort.capabilities()).thenReturn(capabilities);
        when(identityHasher.hash(providerRef)).thenReturn("identity-key");
        when(candidateMapper.snapshot(
                providerRef,
                candidate,
                Instant.parse("2026-07-24T01:00:00Z")
        )).thenReturn(snapshot);
        when(existingProduct.getId()).thenReturn(42L);
        when(persistenceService.materializeStable(providerRef, "identity-key", snapshot))
                .thenReturn(new ProductPersistenceService.MaterializationResult(
                        existingProduct,
                        false
                ));
        ProductMaterializationService service = new ProductMaterializationService(
                productCatalogPort,
                candidateTokenService,
                identityHasher,
                candidateMapper,
                persistenceService,
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC)
        );

        ProductMaterializationService.RecommendationMaterializationResult result =
                service.materializeForRecommendation(candidate);

        assertThat(result.productId()).isEqualTo(42L);
        assertThat(result.created()).isFalse();
        verify(persistenceService).refresh(42L, snapshot);
    }

    @Test
    void materializesNewRecommendationCandidateWithoutRefreshing() {
        ProductCatalogPort productCatalogPort = mock(ProductCatalogPort.class);
        CandidateTokenService candidateTokenService = mock(CandidateTokenService.class);
        ProductIdentityHasher identityHasher = mock(ProductIdentityHasher.class);
        ProductCandidateMapper candidateMapper = mock(ProductCandidateMapper.class);
        ProductPersistenceService persistenceService = mock(ProductPersistenceService.class);
        ProviderProductRef providerRef = ProviderProductRef.stable(
                "fixture",
                "product-1",
                "variant-1",
                "merchant-1"
        );
        ExternalProductCandidate candidate = new ExternalProductCandidate(
                providerRef,
                "Fixture Product",
                null,
                "tops/shirts",
                null,
                null,
                null,
                Instant.parse("2026-07-24T00:00:00Z")
        );
        ProviderCapabilities capabilities = new ProviderCapabilities(
                "fixture",
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
        ProductSnapshot snapshot = mock(ProductSnapshot.class);
        Product createdProduct = mock(Product.class);
        when(productCatalogPort.capabilities()).thenReturn(capabilities);
        when(identityHasher.hash(providerRef)).thenReturn("identity-key");
        when(candidateMapper.snapshot(
                providerRef,
                candidate,
                Instant.parse("2026-07-24T01:00:00Z")
        )).thenReturn(snapshot);
        when(createdProduct.getId()).thenReturn(42L);
        when(persistenceService.materializeStable(providerRef, "identity-key", snapshot))
                .thenReturn(new ProductPersistenceService.MaterializationResult(
                        createdProduct,
                        true
                ));
        ProductMaterializationService service = new ProductMaterializationService(
                productCatalogPort,
                candidateTokenService,
                identityHasher,
                candidateMapper,
                persistenceService,
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC)
        );

        ProductMaterializationService.RecommendationMaterializationResult result =
                service.materializeForRecommendation(candidate);

        assertThat(result.productId()).isEqualTo(42L);
        assertThat(result.created()).isTrue();
        verify(persistenceService, never()).refresh(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void materializesLiveLookupRecommendationAsIdentityOnly() {
        ProductCatalogPort productCatalogPort = mock(ProductCatalogPort.class);
        CandidateTokenService candidateTokenService = mock(CandidateTokenService.class);
        ProductIdentityHasher identityHasher = mock(ProductIdentityHasher.class);
        ProductCandidateMapper candidateMapper = mock(ProductCandidateMapper.class);
        ProductPersistenceService persistenceService = mock(ProductPersistenceService.class);
        ProviderProductRef providerRef = ProviderProductRef.stable(
                "shopify",
                "product-1",
                "variant-1",
                "merchant-1"
        );
        ExternalProductCandidate candidate = new ExternalProductCandidate(
                providerRef,
                "Shopify Product",
                null,
                "tops/shirts",
                null,
                null,
                null,
                Instant.parse("2026-07-24T00:00:00Z")
        );
        ProviderCapabilities capabilities = new ProviderCapabilities(
                "shopify",
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                null,
                true
        );
        Product product = mock(Product.class);
        when(productCatalogPort.capabilities()).thenReturn(capabilities);
        when(identityHasher.hash(providerRef)).thenReturn("identity-key");
        when(product.getId()).thenReturn(42L);
        when(persistenceService.materializeIdentityOnly(providerRef, "identity-key"))
                .thenReturn(new ProductPersistenceService.MaterializationResult(
                        product,
                        true
                ));
        ProductMaterializationService service = new ProductMaterializationService(
                productCatalogPort,
                candidateTokenService,
                identityHasher,
                candidateMapper,
                persistenceService,
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC)
        );

        ProductMaterializationService.RecommendationMaterializationResult result =
                service.materializeForRecommendation(candidate);

        assertThat(result.productId()).isEqualTo(42L);
        assertThat(result.created()).isTrue();
        verify(persistenceService).materializeIdentityOnly(providerRef, "identity-key");
        verifyNoInteractions(candidateMapper);
        verify(persistenceService, never()).refresh(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
