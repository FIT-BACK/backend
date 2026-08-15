package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.product.dto.ProductSearchResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.exception.ProductProviderException;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ProductSearchService {

    private final ProductCatalogPort productCatalogPort;
    private final ProductRepository productRepository;
    private final ProductIdentityHasher identityHasher;
    private final CandidateTokenService candidateTokenService;
    private final ProductResponseMapper responseMapper;

    public ProductSearchService(
            ProductCatalogPort productCatalogPort,
            ProductRepository productRepository,
            ProductIdentityHasher identityHasher,
            CandidateTokenService candidateTokenService,
            ProductResponseMapper responseMapper
    ) {
        this.productCatalogPort = productCatalogPort;
        this.productRepository = productRepository;
        this.identityHasher = identityHasher;
        this.candidateTokenService = candidateTokenService;
        this.responseMapper = responseMapper;
    }

    public ProductSearchResponse search(
            long memberId,
            String keyword,
            String categoryValue,
            String cursor,
            int pageSize
    ) {
        ProductSearchQuery query = query(keyword, categoryValue, cursor, pageSize);
        ProviderCapabilities capabilities = productCatalogPort.capabilities();
        ProductSearchResult result;
        try {
            result = productCatalogPort.search(query);
        } catch (ProductProviderException exception) {
            throw ProductProviderErrorMapper.toBusinessException(exception);
        }

        List<ProductSearchResponse.Item> items = result.items().stream()
                .map(candidate -> item(memberId, capabilities, candidate))
                .toList();
        return new ProductSearchResponse(
                items,
                result.nextCursor(),
                result.nextCursor() != null,
                pageSize,
                false,
                List.of()
        );
    }

    private ProductSearchResponse.Item item(
            long memberId,
            ProviderCapabilities capabilities,
            ExternalProductCandidate candidate
    ) {
        ProviderProductRef providerRef = candidate.providerRef();
        if (!capabilities.provider().equals(providerRef.provider())) {
            throw new BusinessException(ErrorCode.PRODUCT_PROVIDER_RESPONSE_INVALID);
        }
        boolean materializable = providerRef.stable()
                && capabilities.supportsLookup()
                && capabilities.canPersistResult();
        String candidateToken = materializable
                ? candidateTokenService.issue(memberId, providerRef)
                : null;
        Long productId = materializable
                ? findMaterializedProductId(providerRef)
                : null;
        return responseMapper.searchItem(
                candidate,
                productId,
                candidateToken,
                materializable,
                materializable && capabilities.wishlistSupported()
        );
    }

    private Long findMaterializedProductId(ProviderProductRef providerRef) {
        return productRepository.findBySourceApiAndProviderIdentityKey(
                        providerRef.provider(),
                        identityHasher.hash(providerRef)
                )
                .map(Product::getId)
                .orElse(null);
    }

    private static ProductSearchQuery query(
            String keyword,
            String categoryValue,
            String cursor,
            int pageSize
    ) {
        try {
            if (keyword == null || keyword.isBlank()) {
                throw new IllegalArgumentException("keyword must not be blank");
            }
            ProductCategory category = categoryValue == null
                    ? null
                    : ProductCategory.valueOf(categoryValue.trim().toUpperCase(Locale.ROOT));
            return new ProductSearchQuery(keyword, category, cursor, pageSize);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
