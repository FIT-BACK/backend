package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.Money;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductOffer;
import com.fitback.backend.domain.product.service.model.ProductSnapshot;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCategoryMapper;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.net.URI;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ProductCandidateMapper {

    private final ProductCategoryMapper categoryMapper;

    public ProductCandidateMapper(ProductCategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    public ProductCategory category(ExternalProductCandidate candidate) {
        String provider = candidate.providerRef().provider();
        return categoryMapper.map(provider, candidate.categoryPath())
                .filter(category -> category != ProductCategory.OTHER)
                .or(() -> categoryMapper.map(provider, candidate.name()))
                .orElse(ProductCategory.OTHER);
    }

    public ProductPriceResponse price(ProductOffer offer) {
        requireOffer(offer);
        if (offer.salePrice() != null) {
            return price(offer.salePrice(), ProductPriceResponse.Type.SALE, offer.observedAt());
        }
        if (offer.currentPrice() != null) {
            return price(
                    offer.currentPrice(),
                    ProductPriceResponse.Type.CURRENT,
                    offer.observedAt()
            );
        }
        if (offer.regularPrice() != null) {
            return price(offer.regularPrice(), ProductPriceResponse.Type.LIST, offer.observedAt());
        }
        return null;
    }

    public ProductSnapshot snapshot(
            ProviderProductRef expectedProviderRef,
            ExternalProductCandidate candidate,
            Instant snapshotExpiresAt
    ) {
        if (!expectedProviderRef.equals(candidate.providerRef())) {
            throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID);
        }
        ProductOffer offer = requireOffer(candidate.offer());
        Money evidence = firstPrice(offer);
        return new ProductSnapshot(
                candidate.name(),
                candidate.brand(),
                offer.seller(),
                category(candidate),
                toString(candidate.imageUrl()),
                amount(offer.regularPrice()),
                amount(offer.currentPrice()),
                amount(offer.salePrice()),
                evidence == null ? null : evidence.currency(),
                evidence == null ? null : offer.observedAt(),
                toString(offer.purchaseUrl()),
                toString(offer.affiliateUrl()),
                offer.availability(),
                snapshotExpiresAt
        );
    }

    private static ProductOffer requireOffer(ProductOffer offer) {
        if (offer == null) {
            throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID);
        }
        return offer;
    }

    private static ProductPriceResponse price(
            Money money,
            ProductPriceResponse.Type type,
            Instant observedAt
    ) {
        return new ProductPriceResponse(
                money.amount(),
                money.currency(),
                type,
                observedAt
        );
    }

    private static Money firstPrice(ProductOffer offer) {
        if (offer.salePrice() != null) {
            return offer.salePrice();
        }
        if (offer.currentPrice() != null) {
            return offer.currentPrice();
        }
        return offer.regularPrice();
    }

    private static java.math.BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }

    private static String toString(URI uri) {
        return uri == null ? null : uri.toString();
    }
}
