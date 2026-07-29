package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.model.ProductSnapshot;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProductPersistenceService {

    private final ProductRepository productRepository;
    private final TransactionTemplate transactionTemplate;

    public ProductPersistenceService(
            ProductRepository productRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.productRepository = productRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    public Optional<Product> findStableProduct(
            String provider,
            String providerIdentityKey
    ) {
        return productRepository.findBySourceApiAndProviderIdentityKey(
                provider,
                providerIdentityKey
        );
    }

    public MaterializationResult materializeStable(
            ProviderProductRef providerRef,
            String providerIdentityKey,
            ProductSnapshot snapshot
    ) {
        return materialize(
                providerRef,
                providerIdentityKey,
                () -> createSnapshotProduct(providerRef, providerIdentityKey, snapshot)
        );
    }

    public MaterializationResult materializeIdentityOnly(
            ProviderProductRef providerRef,
            String providerIdentityKey
    ) {
        return materialize(
                providerRef,
                providerIdentityKey,
                () -> Product.createIdentityOnly(
                        providerRef.provider(),
                        providerIdentityKey,
                        providerRef.externalProductId(),
                        providerRef.externalVariantId(),
                        providerRef.merchantId()
                )
        );
    }

    private MaterializationResult materialize(
            ProviderProductRef providerRef,
            String providerIdentityKey,
            java.util.function.Supplier<Product> productFactory
    ) {
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                Product existing = findStableProduct(
                        providerRef.provider(),
                        providerIdentityKey
                ).orElse(null);
                if (existing != null) {
                    return new MaterializationResult(existing, false);
                }

                Product product = productFactory.get();
                return new MaterializationResult(productRepository.saveAndFlush(product), true);
            }));
        } catch (DataIntegrityViolationException exception) {
            MaterializationResult concurrentResult = transactionTemplate.execute(status ->
                    findStableProduct(providerRef.provider(), providerIdentityKey)
                            .map(product -> new MaterializationResult(product, false))
                            .orElse(null)
            );
            if (concurrentResult != null) {
                return concurrentResult;
            }
            throw exception;
        }
    }

    private static Product createSnapshotProduct(
            ProviderProductRef providerRef,
            String providerIdentityKey,
            ProductSnapshot snapshot
    ) {
        return Product.createProviderProduct(
                providerRef.provider(),
                providerRef.identityType(),
                providerIdentityKey,
                null,
                providerRef.externalProductId(),
                providerRef.externalVariantId(),
                providerRef.merchantId(),
                ProductStorageMode.SNAPSHOT,
                snapshot.name(),
                snapshot.brandName(),
                snapshot.sellerName(),
                snapshot.category(),
                snapshot.imageUrl(),
                snapshot.listPrice(),
                snapshot.currentPrice(),
                snapshot.salePrice(),
                snapshot.currency(),
                snapshot.priceObservedAt(),
                snapshot.purchaseUrl(),
                snapshot.affiliateUrl(),
                snapshot.availability(),
                snapshot.snapshotExpiresAt()
        );
    }

    @Transactional
    public void refresh(Long productId, ProductSnapshot snapshot) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        refresh(product, snapshot);
    }

    @Transactional
    public void markUnavailable(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.markUnavailable();
    }

    private static void refresh(Product product, ProductSnapshot snapshot) {
        product.refreshSnapshot(
                snapshot.name(),
                snapshot.brandName(),
                snapshot.sellerName(),
                snapshot.category(),
                snapshot.imageUrl(),
                snapshot.listPrice(),
                snapshot.currentPrice(),
                snapshot.salePrice(),
                snapshot.currency(),
                snapshot.priceObservedAt(),
                snapshot.purchaseUrl(),
                snapshot.affiliateUrl(),
                snapshot.availability(),
                snapshot.snapshotExpiresAt()
        );
    }

    public record MaterializationResult(Product product, boolean created) {
    }
}
