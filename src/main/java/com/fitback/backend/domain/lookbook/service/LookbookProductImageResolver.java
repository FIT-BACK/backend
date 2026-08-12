package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.ProductProviderErrorMapper;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LookbookProductImageResolver {

    private final ProductCatalogPort productCatalogPort;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String resolve(Product product) {
        if (product.getStorageMode() == ProductStorageMode.SNAPSHOT) {
            return requireImageUrl(product.getImageUrl());
        }

        ProviderProductRef providerRef = ProviderProductRef.stable(
                product.getSourceApi(),
                product.getExternalProductId(),
                product.getExternalVariantId(),
                product.getMerchantId()
        );
        try {
            ExternalProductCandidate candidate = productCatalogPort.lookup(providerRef)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.PRODUCT_PROVIDER_UNAVAILABLE
                    ));
            return requireImageUrl(
                    candidate.imageUrl() == null ? null : candidate.imageUrl().toString()
            );
        } catch (ProductProviderException exception) {
            throw ProductProviderErrorMapper.toBusinessException(exception);
        }
    }

    private static String requireImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "이미지가 없는 상품은 룩북에 사용할 수 없습니다."
            );
        }
        return imageUrl;
    }
}
