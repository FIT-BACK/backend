package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.dto.ProductReferenceResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductSnapshot;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ProductMaterializationService {

    private final ProductCatalogPort productCatalogPort;
    private final CandidateTokenService candidateTokenService;
    private final ProductIdentityHasher identityHasher;
    private final ProductCandidateMapper candidateMapper;
    private final ProductPersistenceService persistenceService;
    private final Clock clock;

    public ProductMaterializationService(
            ProductCatalogPort productCatalogPort,
            CandidateTokenService candidateTokenService,
            ProductIdentityHasher identityHasher,
            ProductCandidateMapper candidateMapper,
            ProductPersistenceService persistenceService,
            Clock clock
    ) {
        this.productCatalogPort = productCatalogPort;
        this.candidateTokenService = candidateTokenService;
        this.identityHasher = identityHasher;
        this.candidateMapper = candidateMapper;
        this.persistenceService = persistenceService;
        this.clock = clock;
    }

    public ProductReferenceResponse materialize(long memberId, String candidateToken) {
        ProviderProductRef providerRef = candidateTokenService.verify(candidateToken, memberId);
        ProviderCapabilities capabilities = productCatalogPort.capabilities();
        if (!providerRef.provider().equals(capabilities.provider())
                || !providerRef.stable()
                || !capabilities.supportsLookup()
                || !capabilities.canPersistResult()) {
            throw new BusinessException(ErrorCode.PRODUCT_REFERENCE_UNSUPPORTED);
        }

        String providerIdentityKey = identityHasher.hash(providerRef);
        Product existingProduct = persistenceService.findStableProduct(
                        providerRef.provider(),
                        providerIdentityKey
                )
                .orElse(null);
        if (existingProduct != null) {
            return new ProductReferenceResponse(
                    existingProduct.getId(),
                    false,
                    existingProduct.getAvailability()
            );
        }

        ExternalProductCandidate candidate;
        try {
            candidate = productCatalogPort.lookup(providerRef)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.PRODUCT_REFERENCE_INVALID
                    ));
        } catch (ProductProviderException exception) {
            throw ProductProviderErrorMapper.toBusinessException(exception);
        }

        Instant expiresAt = snapshotExpiresAt(capabilities.maxTtl());
        ProductSnapshot snapshot = candidateMapper.snapshot(providerRef, candidate, expiresAt);
        ProductPersistenceService.MaterializationResult result =
                persistenceService.materializeStable(
                        providerRef,
                        providerIdentityKey,
                        snapshot
                );
        return new ProductReferenceResponse(
                result.product().getId(),
                result.created(),
                result.product().getAvailability()
        );
    }

    private Instant snapshotExpiresAt(Duration maxTtl) {
        return maxTtl == null ? null : clock.instant().plus(maxTtl);
    }
}
