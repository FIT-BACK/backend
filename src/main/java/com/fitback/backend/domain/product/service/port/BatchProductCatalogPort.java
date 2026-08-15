package com.fitback.backend.domain.product.service.port;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.util.List;
import java.util.Map;

/**
 * Optional provider capability for resolving multiple stable product identities in one request.
 */
public interface BatchProductCatalogPort {

    int maxLookupBatchSize();

    Map<ProviderProductRef, ExternalProductCandidate> lookupBatch(
            List<ProviderProductRef> providerRefs
    );
}
