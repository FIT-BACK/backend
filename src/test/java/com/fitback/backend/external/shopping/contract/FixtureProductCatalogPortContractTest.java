package com.fitback.backend.external.shopping.contract;

import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.external.shopping.fixture.FixtureShoppingProviderAdapter;

class FixtureProductCatalogPortContractTest extends ProductCatalogPortContractTest {

    private final FixtureShoppingProviderAdapter adapter =
            new FixtureShoppingProviderAdapter();

    @Override
    protected ProductCatalogPort port() {
        return adapter;
    }

    @Override
    protected String provider() {
        return FixtureShoppingProviderAdapter.PROVIDER;
    }

    @Override
    protected ProductSearchQuery matchingQuery() {
        return new ProductSearchQuery("Fixture", null, null, 20);
    }

    @Override
    protected ProviderProductRef existingProductRef() {
        return ProviderProductRef.stable(
                FixtureShoppingProviderAdapter.PROVIDER,
                "fixture-top-001",
                "beige-m",
                "fixture-store"
        );
    }

    @Override
    protected ProviderProductRef missingProductRef() {
        return ProviderProductRef.stable(
                FixtureShoppingProviderAdapter.PROVIDER,
                "missing-product",
                null,
                "fixture-store"
        );
    }
}
