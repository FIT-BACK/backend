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
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
            ExternalProductCandidate candidate = productCatalogPort.lookup(providerRef)
                    .orElse(null);
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
