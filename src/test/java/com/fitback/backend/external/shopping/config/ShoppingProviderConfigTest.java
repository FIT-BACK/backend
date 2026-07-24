package com.fitback.backend.external.shopping.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.external.shopping.fixture.FixtureShoppingProviderAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ShoppingProviderConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ShoppingProviderConfig.class);

    @Test
    void usesFixtureAsDefaultProviderWithoutShopifyCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProductCatalogPort.class);
            assertThat(context).hasSingleBean(FixtureShoppingProviderAdapter.class);
            assertThat(context.getBean(ProductCatalogPort.class))
                    .isSameAs(context.getBean(FixtureShoppingProviderAdapter.class));

            ShoppingProviderProperties properties =
                    context.getBean(ShoppingProviderProperties.class);
            assertThat(properties.provider())
                    .isEqualTo(ShoppingProviderProperties.Provider.FIXTURE);
            assertThat(properties.shopify().enabled()).isFalse();
        });
    }

    @Test
    void rejectsShopifyProviderWhileFeatureIsDisabled() {
        contextRunner
                .withPropertyValues("shopping.provider=shopify")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Shopify runtime provider is unavailable while selection remains pending"
                            );
                });
    }

    @Test
    void rejectsAccidentalShopifyFeatureActivation() {
        contextRunner
                .withPropertyValues("shopping.shopify.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "shopping.shopify.enabled must remain false"
                            );
                });
    }
}
