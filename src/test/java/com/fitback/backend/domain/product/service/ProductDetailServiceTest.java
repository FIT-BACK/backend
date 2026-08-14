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
import com.fitback.backend.domain.product.service.port.BatchProductCatalogPort;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.observability.RecommendationPerformanceTrace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            assertThat(dependencies.service().getDetail(1L)).isEqualTo(expected);
            trace = scope.snapshot();
        }
        verify(dependencies.productCatalogPort()).lookup(providerRef);
        verify(dependencies.candidateMapper()).snapshot(
                providerRef,
                candidate,
                NOW.plus(Duration.ofHours(1))
        );
        verify(dependencies.persistenceService()).refresh(1L, snapshot);
        assertThat(trace.lookupCatalogCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.inputSize()).isEqualTo(1));
        assertThat(trace.lookupCatalogTiming().invocationCount()).isEqualTo(1);
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

    @Test
    void batchHydratesOneIdentityOnlyProductAndRecordsBatchInputSize() {
        BatchDependencies dependencies = batchDependencies();
        Product product = identityOnlyProduct(
                1L,
                "external-product-1",
                "variant-1",
                "merchant-1"
        );
        ProviderProductRef providerRef = providerRef(product);
        ExternalProductCandidate candidate = mock(ExternalProductCandidate.class);
        ProductDetailResponse expected = detailResponse(
                1L,
                "Live Product",
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE
        );
        dependencies.batchCatalogPort().setCandidates(Map.of(providerRef, candidate));
        when(dependencies.responseMapper().detail(
                1L,
                candidate,
                ProductDataStatus.LIVE
        )).thenReturn(expected);

        ProductDetailBatchResult result;
        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            result = dependencies.service().lookupIdentityOnlyDetails(List.of(product));
            trace = scope.snapshot();
        }

        assertThat(result.detailsByProductId()).containsExactly(Map.entry(1L, expected));
        assertThat(result.failuresByProductId()).isEmpty();
        assertThat(dependencies.batchCatalogPort().requestedBatches())
                .containsExactly(List.of(providerRef));
        assertThat(dependencies.batchCatalogPort().singleLookupCallCount()).isZero();
        assertThat(trace.lookupCatalogCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.inputSize()).isEqualTo(1));
    }

    @Test
    void batchHydratesTenIdentityOnlyProductsInOneLookupRequest() {
        BatchDependencies dependencies = batchDependencies();
        List<Product> products = new ArrayList<>();
        Map<ProviderProductRef, ExternalProductCandidate> candidates = new LinkedHashMap<>();
        for (long index = 1; index <= 10; index++) {
            Product product = identityOnlyProduct(
                    index,
                    "external-product-" + index,
                    "variant-" + index,
                    "merchant-" + index
            );
            ProviderProductRef providerRef = providerRef(product);
            ExternalProductCandidate candidate = mock(ExternalProductCandidate.class);
            ProductDetailResponse expected = detailResponse(
                    index,
                    "Live Product " + index,
                    ProductAvailability.AVAILABLE,
                    ProductDataStatus.LIVE
            );
            products.add(product);
            candidates.put(providerRef, candidate);
            when(dependencies.responseMapper().detail(
                    index,
                    candidate,
                    ProductDataStatus.LIVE
            )).thenReturn(expected);
        }
        dependencies.batchCatalogPort().setCandidates(candidates);

        ProductDetailBatchResult result;
        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            result = dependencies.service().lookupIdentityOnlyDetails(products);
            trace = scope.snapshot();
        }

        assertThat(result.detailsByProductId()).hasSize(10);
        assertThat(result.failuresByProductId()).isEmpty();
        assertThat(dependencies.batchCatalogPort().requestedBatches())
                .singleElement()
                .satisfies(batch -> assertThat(batch).hasSize(10));
        assertThat(dependencies.batchCatalogPort().singleLookupCallCount()).isZero();
        assertThat(trace.lookupCatalogCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.inputSize()).isEqualTo(10));
        assertThat(trace.lookupCatalogTiming().invocationCount()).isEqualTo(1);
    }

    @Test
    void batchHydrationChunksFiftyOneDistinctIdentitiesAtTheShopifyMaximum() {
        BatchDependencies dependencies = batchDependencies();
        List<Product> products = new ArrayList<>();
        for (long index = 1; index <= 51; index++) {
            products.add(identityOnlyProduct(
                    index,
                    "external-product-" + index,
                    "variant-" + index,
                    "merchant-" + index
            ));
        }

        ProductDetailBatchResult result;
        RecommendationPerformanceTrace.Snapshot trace;
        try (RecommendationPerformanceTrace.Scope scope =
                     RecommendationPerformanceTrace.beginIfRequested(
                             RecommendationPerformanceTrace.REQUEST_VALUE
                     )) {
            result = dependencies.service().lookupIdentityOnlyDetails(products);
            trace = scope.snapshot();
        }

        assertThat(result.detailsByProductId()).isEmpty();
        assertThat(result.failuresByProductId()).hasSize(51);
        assertThat(dependencies.batchCatalogPort().requestedBatches())
                .extracting(List::size)
                .containsExactly(50, 1);
        assertThat(trace.lookupCatalogCalls())
                .extracting(call -> call.inputSize())
                .containsExactly(50, 1);
        assertThat(trace.lookupCatalogTiming().invocationCount()).isEqualTo(2);
    }

    @Test
    void batchFansOutDuplicateIdentityAndKeepsPartialMissAsExistingUnavailableFallback() {
        BatchDependencies dependencies = batchDependencies();
        Product first = identityOnlyProduct(
                1L,
                "shared-product",
                "shared-variant",
                "shared-merchant"
        );
        Product duplicate = identityOnlyProduct(
                2L,
                "shared-product",
                "shared-variant",
                "shared-merchant"
        );
        Product missing = identityOnlyProduct(
                3L,
                "missing-product",
                "missing-variant",
                "missing-merchant"
        );
        ProviderProductRef sharedRef = providerRef(first);
        ExternalProductCandidate candidate = mock(ExternalProductCandidate.class);
        dependencies.batchCatalogPort().setCandidates(Map.of(sharedRef, candidate));
        when(dependencies.responseMapper().detail(
                1L,
                candidate,
                ProductDataStatus.LIVE
        )).thenReturn(detailResponse(
                1L,
                "First Product",
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE
        ));
        when(dependencies.responseMapper().detail(
                2L,
                candidate,
                ProductDataStatus.LIVE
        )).thenReturn(detailResponse(
                2L,
                "Duplicate Product",
                ProductAvailability.AVAILABLE,
                ProductDataStatus.LIVE
        ));

        ProductDetailBatchResult result = dependencies.service().lookupIdentityOnlyDetails(
                List.of(first, duplicate, missing)
        );

        assertThat(dependencies.batchCatalogPort().requestedBatches())
                .containsExactly(List.of(sharedRef, providerRef(missing)));
        assertThat(result.detailsByProductId()).containsKeys(1L, 2L);
        assertThat(result.failuresByProductId()).containsExactly(
                Map.entry(3L, ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE)
        );
        assertThat(dependencies.batchCatalogPort().singleLookupCallCount()).isZero();
    }

    @Test
    void batchMapsProviderFailureToTheExistingPerItemErrorCode() {
        BatchDependencies dependencies = batchDependencies();
        Product first = identityOnlyProduct(
                1L,
                "external-product-1",
                "variant-1",
                "merchant-1"
        );
        Product second = identityOnlyProduct(
                2L,
                "external-product-2",
                "variant-2",
                "merchant-2"
        );
        dependencies.batchCatalogPort().setFailure(new ProductProviderException(
                "fixture",
                ProductProviderFailure.RATE_LIMITED
        ));

        ProductDetailBatchResult result = dependencies.service().lookupIdentityOnlyDetails(
                List.of(first, second)
        );

        assertThat(result.detailsByProductId()).isEmpty();
        assertThat(result.failuresByProductId()).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, ErrorCode.PRODUCT_PROVIDER_QUOTA_EXCEEDED,
                2L, ErrorCode.PRODUCT_PROVIDER_QUOTA_EXCEEDED
        ));
        assertThat(dependencies.batchCatalogPort().requestedBatches())
                .containsExactly(List.of(providerRef(first), providerRef(second)));
        assertThat(dependencies.batchCatalogPort().singleLookupCallCount()).isZero();
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
        return identityOnlyProduct(1L, "external-product", "variant-1", "merchant-1");
    }

    private static Product identityOnlyProduct(
            Long productId,
            String externalProductId,
            String externalVariantId,
            String merchantId
    ) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getSourceApi()).thenReturn("fixture");
        when(product.getIdentityStrategy()).thenReturn(ProviderIdentityType.PROVIDER_KEY);
        when(product.getProviderIdentityKey()).thenReturn("identity-" + productId);
        when(product.getExternalProductId()).thenReturn(externalProductId);
        when(product.getExternalVariantId()).thenReturn(externalVariantId);
        when(product.getMerchantId()).thenReturn(merchantId);
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

    private static ProviderProductRef providerRef(Product product) {
        return ProviderProductRef.stable(
                product.getSourceApi(),
                product.getExternalProductId(),
                product.getExternalVariantId(),
                product.getMerchantId()
        );
    }

    private static ProductDetailResponse detailResponse(
            String name,
            ProductAvailability availability,
            ProductDataStatus dataStatus
    ) {
        return detailResponse(1L, name, availability, dataStatus);
    }

    private static ProductDetailResponse detailResponse(
            Long productId,
            String name,
            ProductAvailability availability,
            ProductDataStatus dataStatus
    ) {
        return new ProductDetailResponse(
                productId,
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

    private static BatchDependencies batchDependencies() {
        RecordingBatchCatalogPort productCatalogPort = new RecordingBatchCatalogPort();
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductCandidateMapper candidateMapper = mock(ProductCandidateMapper.class);
        ProductResponseMapper responseMapper = mock(ProductResponseMapper.class);
        ProductPersistenceService persistenceService = mock(ProductPersistenceService.class);
        ProductDetailService service = new ProductDetailService(
                productCatalogPort,
                productRepository,
                candidateMapper,
                responseMapper,
                persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new BatchDependencies(
                service,
                productCatalogPort,
                responseMapper
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

    private record BatchDependencies(
            ProductDetailService service,
            RecordingBatchCatalogPort batchCatalogPort,
            ProductResponseMapper responseMapper
    ) {
    }

    private static final class RecordingBatchCatalogPort
            implements ProductCatalogPort, BatchProductCatalogPort {

        private final List<List<ProviderProductRef>> requestedBatches = new ArrayList<>();
        private final AtomicInteger singleLookupCallCount = new AtomicInteger();
        private Map<ProviderProductRef, ExternalProductCandidate> candidates = Map.of();
        private ProductProviderException failure;

        @Override
        public ProviderCapabilities capabilities() {
            return ProductDetailServiceTest.capabilities();
        }

        @Override
        public com.fitback.backend.domain.product.service.model.ProductSearchResult search(
                com.fitback.backend.domain.product.service.model.ProductSearchQuery query
        ) {
            throw new UnsupportedOperationException("not used by detail hydration");
        }

        @Override
        public Optional<ExternalProductCandidate> lookup(ProviderProductRef providerRef) {
            singleLookupCallCount.incrementAndGet();
            return Optional.ofNullable(candidates.get(providerRef));
        }

        @Override
        public int maxLookupBatchSize() {
            return 50;
        }

        @Override
        public Map<ProviderProductRef, ExternalProductCandidate> lookupBatch(
                List<ProviderProductRef> providerRefs
        ) {
            requestedBatches.add(List.copyOf(providerRefs));
            if (failure != null) {
                throw failure;
            }
            return candidates;
        }

        void setCandidates(Map<ProviderProductRef, ExternalProductCandidate> candidates) {
            this.candidates = Map.copyOf(candidates);
            this.failure = null;
        }

        void setFailure(ProductProviderException failure) {
            this.failure = failure;
        }

        List<List<ProviderProductRef>> requestedBatches() {
            return List.copyOf(requestedBatches);
        }

        int singleLookupCallCount() {
            return singleLookupCallCount.get();
        }
    }
}
