package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.exception.ProductProviderFailure;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
import com.fitback.backend.domain.product.service.model.ProductSnapshot;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderIdentityType;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductDetailServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @Test
    void refreshesSnapshotAndReturnsLiveDetailWhenLookupSucceeds() {
        Dependencies dependencies = dependencies();
        Product product = providerProduct();
        ProviderProductRef providerRef = providerRef();
        ExternalProductCandidate candidate = mock(ExternalProductCandidate.class);
        ProductSnapshot snapshot = mock(ProductSnapshot.class);
        ProductDetailResponse expected = detailResponse(
                "Live Product",
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE
        );
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.productCatalogPort().lookup(providerRef))
                .thenReturn(Optional.of(candidate));
        when(dependencies.candidateMapper().snapshot(
                providerRef,
                candidate,
                NOW.plus(Duration.ofHours(1))
        )).thenReturn(snapshot);
        when(dependencies.responseMapper().detail(
                1L,
                candidate,
                ProductDataStatus.LIVE
        )).thenReturn(expected);

        assertThat(dependencies.service().getDetail(1L)).isEqualTo(expected);
        verify(dependencies.productCatalogPort()).lookup(providerRef);
        verify(dependencies.candidateMapper()).snapshot(
                providerRef,
                candidate,
                NOW.plus(Duration.ofHours(1))
        );
        verify(dependencies.persistenceService()).refresh(1L, snapshot);
    }

    @Test
    void marksProductUnavailableWhenLookupReturnsEmpty() {
        Dependencies dependencies = dependencies();
        Product product = providerProduct();
        ProductDetailResponse expected = detailResponse(
                "Cached Product",
                ProductAvailability.UNAVAILABLE,
                ProductDataStatus.LIVE
        );
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.productCatalogPort().lookup(providerRef()))
                .thenReturn(Optional.empty());
        when(dependencies.responseMapper().detail(
                product,
                ProductAvailability.UNAVAILABLE,
                ProductDataStatus.LIVE
        )).thenReturn(expected);

        assertThat(dependencies.service().getDetail(1L)).isEqualTo(expected);
        verify(dependencies.persistenceService()).markUnavailable(1L);
        verify(dependencies.responseMapper()).detail(
                product,
                ProductAvailability.UNAVAILABLE,
                ProductDataStatus.LIVE
        );
        verifyNoInteractions(dependencies.candidateMapper());
    }

    @Test
    void returnsLiveIdentityOnlyDetailWithoutPersistingSnapshot() {
        Dependencies dependencies = dependencies();
        Product product = identityOnlyProduct();
        ExternalProductCandidate candidate = mock(ExternalProductCandidate.class);
        ProductDetailResponse expected = detailResponse(
                "Live Product",
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE
        );
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.productCatalogPort().lookup(providerRef()))
                .thenReturn(Optional.of(candidate));
        when(dependencies.responseMapper().detail(
                1L,
                candidate,
                ProductDataStatus.LIVE
        )).thenReturn(expected);

        assertThat(dependencies.service().getDetail(1L)).isEqualTo(expected);
        verify(dependencies.responseMapper()).detail(
                1L,
                candidate,
                ProductDataStatus.LIVE
        );
        verifyNoInteractions(dependencies.candidateMapper());
        verify(dependencies.persistenceService(), never()).refresh(any(), any());
        verify(dependencies.persistenceService(), never()).markUnavailable(any());
    }

    @Test
    void identityOnlyLookupMissDoesNotPersistUnavailableSnapshotState() {
        Dependencies dependencies = dependencies();
        Product product = identityOnlyProduct();
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.productCatalogPort().lookup(providerRef()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> dependencies.service().getDetail(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE)
                );
        verify(dependencies.persistenceService(), never()).markUnavailable(any());
        verifyNoInteractions(dependencies.candidateMapper());
        verifyNoInteractions(dependencies.responseMapper());
    }

    @Test
    void mapsMalformedLookupResponseInsteadOfMaskingItWithSnapshot() {
        Dependencies dependencies = dependencies();
        Product product = providerProduct();
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.productCatalogPort().lookup(any(ProviderProductRef.class)))
                .thenThrow(new ProductProviderException(
                        "fixture",
                        ProductProviderFailure.MALFORMED_RESPONSE
                ));

        assertThatThrownBy(() -> dependencies.service().getDetail(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID)
                );
        verifyNoInteractions(dependencies.responseMapper());
    }

    @Test
    void usesStaleSnapshotForTransientLookupTimeout() {
        Dependencies dependencies = dependencies();
        Product product = providerProduct();
        ProductDetailResponse expected = new ProductDetailResponse(
                1L,
                null,
                "Cached Product",
                null,
                null,
                null,
                null,
                null,
                null,
                ProductAvailability.TEMPORARILY_UNRESOLVED,
                ProductDataStatus.STALE_SNAPSHOT,
                List.of(),
                false
        );
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.productCatalogPort().lookup(any(ProviderProductRef.class)))
                .thenThrow(new ProductProviderException(
                        "fixture",
                        ProductProviderFailure.TIMEOUT
                ));
        when(dependencies.responseMapper().detail(
                product,
                ProductAvailability.TEMPORARILY_UNRESOLVED,
                ProductDataStatus.STALE_SNAPSHOT
        )).thenReturn(expected);

        assertThat(dependencies.service().getDetail(1L)).isEqualTo(expected);
    }

    @Test
    void legacySnapshotIdentityDoesNotUseLiveLookupEvenWithExternalProductId() {
        Dependencies dependencies = dependencies();
        Product product = mock(Product.class);
        ProductDetailResponse expected = new ProductDetailResponse(
                1L,
                null,
                "Legacy Product",
                null,
                null,
                null,
                null,
                null,
                null,
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE,
                List.of(),
                false
        );
        when(product.getSourceApi()).thenReturn("fixture");
        when(product.getIdentityStrategy()).thenReturn(ProviderIdentityType.SNAPSHOT_UUID);
        when(product.getExternalProductId()).thenReturn("legacy-external-id");
        when(product.hasDisplayData()).thenReturn(true);
        when(product.getSnapshotExpiresAt()).thenReturn(NOW.plus(Duration.ofHours(1)));
        when(product.getAvailability()).thenReturn(ProductAvailability.AVAILABLE);
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.responseMapper().detail(
                product,
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE
        )).thenReturn(expected);

        assertThat(dependencies.service().getDetail(1L)).isEqualTo(expected);
        verify(dependencies.productCatalogPort(), never()).lookup(any());
    }

    @Test
    void legacySnapshotIdentityReturnsStaleSnapshotWhenSnapshotExpired() {
        Dependencies dependencies = dependencies();
        Product product = mock(Product.class);
        ProductDetailResponse expected = detailResponse(
                "Legacy Product",
                ProductAvailability.UNKNOWN,
                ProductDataStatus.STALE_SNAPSHOT
        );
        when(product.getSourceApi()).thenReturn("fixture");
        when(product.getIdentityStrategy()).thenReturn(ProviderIdentityType.SNAPSHOT_UUID);
        when(product.getExternalProductId()).thenReturn("legacy-external-id");
        when(product.hasDisplayData()).thenReturn(true);
        when(product.getSnapshotExpiresAt()).thenReturn(NOW.minus(Duration.ofHours(1)));
        when(dependencies.productRepository().findById(1L)).thenReturn(Optional.of(product));
        when(dependencies.responseMapper().detail(
                product,
                ProductAvailability.UNKNOWN,
                ProductDataStatus.STALE_SNAPSHOT
        )).thenReturn(expected);

        assertThat(dependencies.service().getDetail(1L)).isEqualTo(expected);
        verify(dependencies.productCatalogPort(), never()).lookup(any());
        verify(dependencies.responseMapper()).detail(
                product,
                ProductAvailability.UNKNOWN,
                ProductDataStatus.STALE_SNAPSHOT
        );
    }

    private static Product providerProduct() {
        Product product = mock(Product.class);
        when(product.getSourceApi()).thenReturn("fixture");
        when(product.getIdentityStrategy()).thenReturn(ProviderIdentityType.PROVIDER_KEY);
        when(product.getProviderIdentityKey()).thenReturn("identity-key");
        when(product.getExternalProductId()).thenReturn("external-product");
        when(product.getExternalVariantId()).thenReturn("variant-1");
        when(product.getMerchantId()).thenReturn("merchant-1");
        when(product.getStorageMode()).thenReturn(ProductStorageMode.SNAPSHOT);
        when(product.hasDisplayData()).thenReturn(true);
        return product;
    }

    private static Product identityOnlyProduct() {
        Product product = providerProduct();
        when(product.getStorageMode()).thenReturn(ProductStorageMode.IDENTITY_ONLY);
        when(product.hasDisplayData()).thenReturn(false);
        return product;
    }

    private static ProviderProductRef providerRef() {
        return ProviderProductRef.stable(
                "fixture",
                "external-product",
                "variant-1",
                "merchant-1"
        );
    }

    private static ProductDetailResponse detailResponse(
            String name,
            ProductAvailability availability,
            ProductDataStatus dataStatus
    ) {
        return new ProductDetailResponse(
                1L,
                null,
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                availability,
                dataStatus,
                List.of(),
                false
        );
    }

    private static Dependencies dependencies() {
        ProductCatalogPort productCatalogPort = mock(ProductCatalogPort.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductCandidateMapper candidateMapper = mock(ProductCandidateMapper.class);
        ProductResponseMapper responseMapper = mock(ProductResponseMapper.class);
        ProductPersistenceService persistenceService = mock(ProductPersistenceService.class);
        when(productCatalogPort.capabilities()).thenReturn(capabilities());
        ProductDetailService service = new ProductDetailService(
                productCatalogPort,
                productRepository,
                candidateMapper,
                responseMapper,
                persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new Dependencies(
                service,
                productCatalogPort,
                productRepository,
                candidateMapper,
                responseMapper,
                persistenceService
        );
    }

    private static ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
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
    }

    private record Dependencies(
            ProductDetailService service,
            ProductCatalogPort productCatalogPort,
            ProductRepository productRepository,
            ProductCandidateMapper candidateMapper,
            ProductResponseMapper responseMapper,
            ProductPersistenceService persistenceService
    ) {
    }
}
