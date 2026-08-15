package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ProductDetailService {

    private final ProductCatalogPort productCatalogPort;
    private final ProductRepository productRepository;
    private final ProductCandidateMapper candidateMapper;
    private final ProductResponseMapper responseMapper;
    private final ProductPersistenceService persistenceService;
    private final Clock clock;

    public ProductDetailService(
            ProductCatalogPort productCatalogPort,
            ProductRepository productRepository,
            ProductCandidateMapper candidateMapper,
            ProductResponseMapper responseMapper,
            ProductPersistenceService persistenceService,
            Clock clock
    ) {
        this.productCatalogPort = productCatalogPort;
        this.productRepository = productRepository;
        this.candidateMapper = candidateMapper;
        this.responseMapper = responseMapper;
        this.persistenceService = persistenceService;
        this.clock = clock;
    }

    public ProductDetailResponse getDetail(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        ProviderCapabilities capabilities = productCatalogPort.capabilities();

        if (!product.getSourceApi().equals(capabilities.provider())
                || !capabilities.supportsLookup()
                || product.getIdentityStrategy() != ProviderIdentityType.PROVIDER_KEY
                || product.getProviderIdentityKey() == null
                || product.getExternalProductId() == null) {
            return snapshotOrThrow(product, ProductAvailability.UNKNOWN);
        }

        ProviderProductRef providerRef = ProviderProductRef.stable(
                product.getSourceApi(),
                product.getExternalProductId(),
                product.getExternalVariantId(),
                product.getMerchantId()
        );
        try {
            ExternalProductCandidate candidate = RecommendationPerformanceTrace.measureLookupCatalog(
                    1,
                    () -> productCatalogPort.lookup(providerRef)
            ).orElse(null);
            if (candidate == null) {
                if (product.getStorageMode() == ProductStorageMode.IDENTITY_ONLY) {
                    throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE);
                }
                persistenceService.markUnavailable(productId);
                if (!product.hasDisplayData()) {
                    throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE);
                }
                return responseMapper.detail(
                        product,
                        ProductAvailability.UNAVAILABLE,
                        ProductDataStatus.LIVE
                );
            }

            if (product.getStorageMode() == ProductStorageMode.IDENTITY_ONLY) {
                return responseMapper.detail(productId, candidate, ProductDataStatus.LIVE);
            }
            ProductSnapshot snapshot = candidateMapper.snapshot(
                    providerRef,
                    candidate,
                    snapshotExpiresAt(capabilities.maxTtl())
            );
            persistenceService.refresh(productId, snapshot);
            return responseMapper.detail(productId, candidate, ProductDataStatus.LIVE);
        } catch (ProductProviderException exception) {
            if (product.getStorageMode() != ProductStorageMode.IDENTITY_ONLY
                    && allowsSnapshotFallback(exception)
                    && product.hasDisplayData()) {
                return responseMapper.detail(
                        product,
                        ProductAvailability.TEMPORARILY_UNRESOLVED,
                        ProductDataStatus.STALE_SNAPSHOT
                );
            }
            throw ProductProviderErrorMapper.toBusinessException(exception);
        }
    }

    public ProductDetailBatchResult lookupIdentityOnlyDetails(List<Product> products) {
        Objects.requireNonNull(products, "products must not be null");
        if (!(productCatalogPort instanceof BatchProductCatalogPort batchCatalogPort)) {
            return ProductDetailBatchResult.empty();
        }

        ProviderCapabilities capabilities = productCatalogPort.capabilities();
        LinkedHashMap<ProviderProductRef, List<Product>> productsByRef = new LinkedHashMap<>();
        for (Product product : products) {
            Objects.requireNonNull(product, "products must not contain null");
            if (!isBatchLookupEligible(product, capabilities)) {
                continue;
            }
            ProviderProductRef providerRef = ProviderProductRef.stable(
                    product.getSourceApi(),
                    product.getExternalProductId(),
                    product.getExternalVariantId(),
                    product.getMerchantId()
            );
            productsByRef.computeIfAbsent(providerRef, ignored -> new ArrayList<>())
                    .add(product);
        }
        if (productsByRef.isEmpty()) {
            return ProductDetailBatchResult.empty();
        }

        int batchSize = batchCatalogPort.maxLookupBatchSize();
        if (batchSize <= 0) {
            return ProductDetailBatchResult.empty();
        }

        List<Map.Entry<ProviderProductRef, List<Product>>> entries =
                new ArrayList<>(productsByRef.entrySet());
        Map<Long, ProductDetailResponse> detailsByProductId = new LinkedHashMap<>();
        Map<Long, ErrorCode> failuresByProductId = new LinkedHashMap<>();
        for (int start = 0; start < entries.size(); start += batchSize) {
            List<Map.Entry<ProviderProductRef, List<Product>>> batchEntries = entries.subList(
                    start,
                    Math.min(start + batchSize, entries.size())
            );
            List<ProviderProductRef> providerRefs = batchEntries.stream()
                    .map(Map.Entry::getKey)
                    .toList();
            try {
                Map<ProviderProductRef, ExternalProductCandidate> candidates =
                        RecommendationPerformanceTrace.measureLookupCatalog(
                                providerRefs.size(),
                                () -> batchCatalogPort.lookupBatch(providerRefs)
                        );
                for (Map.Entry<ProviderProductRef, List<Product>> batchEntry : batchEntries) {
                    ExternalProductCandidate candidate = candidates.get(batchEntry.getKey());
                    if (candidate == null) {
                        addFailure(
                                batchEntry.getValue(),
                                ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE,
                                failuresByProductId
                        );
                        continue;
                    }
                    for (Product product : batchEntry.getValue()) {
                        detailsByProductId.put(
                                product.getId(),
                                responseMapper.detail(
                                        product.getId(),
                                        candidate,
                                        ProductDataStatus.LIVE
                                )
                        );
                    }
                }
            } catch (ProductProviderException exception) {
                ErrorCode errorCode = ProductProviderErrorMapper
                        .toBusinessException(exception)
                        .getErrorCode();
                for (Map.Entry<ProviderProductRef, List<Product>> batchEntry : batchEntries) {
                    addFailure(batchEntry.getValue(), errorCode, failuresByProductId);
                }
            }
        }
        return new ProductDetailBatchResult(detailsByProductId, failuresByProductId);
    }

    private static boolean isBatchLookupEligible(
            Product product,
            ProviderCapabilities capabilities
    ) {
        return product.getStorageMode() == ProductStorageMode.IDENTITY_ONLY
                && product.getSourceApi().equals(capabilities.provider())
                && capabilities.supportsLookup()
                && product.getIdentityStrategy() == ProviderIdentityType.PROVIDER_KEY
                && product.getProviderIdentityKey() != null
                && product.getExternalProductId() != null;
    }

    private static void addFailure(
            List<Product> products,
            ErrorCode errorCode,
            Map<Long, ErrorCode> failuresByProductId
    ) {
        for (Product product : products) {
            failuresByProductId.put(product.getId(), errorCode);
        }
    }

    private static boolean allowsSnapshotFallback(ProductProviderException exception) {
        return switch (exception.getFailure()) {
            case TIMEOUT, UNAVAILABLE -> true;
            case RATE_LIMITED, QUOTA_EXCEEDED, AUTHENTICATION_FAILED,
                    MALFORMED_RESPONSE -> false;
        };
    }

    private ProductDetailResponse snapshotOrThrow(
            Product product,
            ProductAvailability unresolvedAvailability
    ) {
        if (!product.hasDisplayData()) {
            throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE);
        }
        boolean fresh = product.getSnapshotExpiresAt() != null
                && product.getSnapshotExpiresAt().isAfter(clock.instant());
        return responseMapper.detail(
                product,
                fresh ? product.getAvailability() : unresolvedAvailability,
                fresh ? ProductDataStatus.LIVE : ProductDataStatus.STALE_SNAPSHOT
        );
    }

    private Instant snapshotExpiresAt(Duration maxTtl) {
        return maxTtl == null ? null : clock.instant().plus(maxTtl);
    }
}
