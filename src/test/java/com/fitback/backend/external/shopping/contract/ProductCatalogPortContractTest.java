package com.fitback.backend.external.shopping.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import org.junit.jupiter.api.Test;

public abstract class ProductCatalogPortContractTest {

    protected abstract ProductCatalogPort port();

    protected abstract String provider();

    protected abstract ProductSearchQuery matchingQuery();

    protected abstract ProviderProductRef existingProductRef();

    protected abstract ProviderProductRef missingProductRef();

    @Test
    void capabilitiesIdentifyTheConfiguredProvider() {
        assertThat(port().capabilities().provider()).isEqualTo(provider());
    }

    @Test
    void searchReturnsOnlyCandidatesOwnedByTheProviderWithinRequestedPageSize() {
        ProductSearchQuery query = matchingQuery();
        ProductSearchResult result = port().search(query);

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSizeLessThanOrEqualTo(query.pageSize());
        assertThat(result.items())
                .extracting(ExternalProductCandidate::providerRef)
                .allMatch(reference -> reference.provider().equals(provider()));
    }

    @Test
    void lookupPreservesStableProviderIdentityAndReturnsEmptyForUnknownIdentity() {
        assertThat(port().lookup(existingProductRef()))
                .get()
                .extracting(ExternalProductCandidate::providerRef)
                .isEqualTo(existingProductRef());
        assertThat(port().lookup(missingProductRef())).isEmpty();
    }
}
