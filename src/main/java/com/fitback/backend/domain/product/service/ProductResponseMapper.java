package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import com.fitback.backend.domain.product.dto.ProductSearchResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductResponseMapper {

    private final ProductCandidateMapper candidateMapper;

    public ProductResponseMapper(ProductCandidateMapper candidateMapper) {
        this.candidateMapper = candidateMapper;
    }

    public ProductSearchResponse.Item searchItem(
            ExternalProductCandidate candidate,
            Long productId,
            String candidateToken,
            boolean detailSupported,
            boolean saveSupported
    ) {
        return new ProductSearchResponse.Item(
                productId,
                candidateToken,
                toString(candidate.imageUrl()),
                candidate.name(),
                candidate.brand(),
                candidate.offer() == null ? null : candidate.offer().seller(),
                candidateMapper.category(candidate),
                candidateMapper.price(candidate.offer()),
                candidate.offer().availability(),
                detailSupported,
                saveSupported
        );
    }

    public ProductDetailResponse detail(
            Long productId,
            ExternalProductCandidate candidate,
            ProductDataStatus dataStatus
    ) {
        return new ProductDetailResponse(
                productId,
                toString(candidate.imageUrl()),
                candidate.name(),
                candidate.brand(),
                candidate.offer().seller(),
                candidateMapper.category(candidate),
                candidateMapper.price(candidate.offer()),
                toString(candidate.offer().purchaseUrl()),
                toString(candidate.offer().affiliateUrl()),
                candidate.offer().availability(),
                dataStatus,
                List.of(),
                false
        );
    }

    public ProductDetailResponse detail(
            Product product,
            ProductAvailability availability,
            ProductDataStatus dataStatus
    ) {
        return new ProductDetailResponse(
                product.getId(),
                product.getImageUrl(),
                product.getName(),
                product.getBrandName(),
                product.getSellerName(),
                product.getCategory(),
                price(product),
                product.getPurchaseUrl(),
                product.getAffiliateUrl(),
                availability,
                dataStatus,
                List.of(),
                false
        );
    }

    private static ProductPriceResponse price(Product product) {
        if (product.getSalePrice() != null) {
            return price(product, product.getSalePrice(), ProductPriceResponse.Type.SALE);
        }
        if (product.getCurrentPrice() != null) {
            return price(
                    product,
                    product.getCurrentPrice(),
                    ProductPriceResponse.Type.CURRENT
            );
        }
        if (product.getListPrice() != null) {
            return price(product, product.getListPrice(), ProductPriceResponse.Type.LIST);
        }
        return null;
    }

    private static ProductPriceResponse price(
            Product product,
            BigDecimal amount,
            ProductPriceResponse.Type type
    ) {
        return new ProductPriceResponse(
                amount,
                product.getCurrency(),
                type,
                product.getPriceObservedAt()
        );
    }

    private static String toString(URI uri) {
        return uri == null ? null : uri.toString();
    }
}
