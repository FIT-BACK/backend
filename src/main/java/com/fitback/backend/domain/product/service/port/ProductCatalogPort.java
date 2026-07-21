package com.fitback.backend.domain.product.service.port;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.util.Optional;

public interface ProductCatalogPort {

    ProviderCapabilities capabilities();

    ProductSearchResult search(ProductSearchQuery query);

    Optional<ExternalProductCandidate> lookup(ProviderProductRef providerRef);
}
