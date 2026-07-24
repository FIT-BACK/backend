package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.product.dto.ProductReferenceResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
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
}
