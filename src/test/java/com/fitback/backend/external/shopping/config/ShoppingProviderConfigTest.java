package com.fitback.backend.external.shopping.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.external.shopping.fixture.FixtureShoppingProviderAdapter;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogAdapter;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogClient;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

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
            assertThat(properties.candidateToken().ttl()).isEqualTo(Duration.ofMinutes(10));
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
                                    "shopping.shopify.enabled must be true"
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
                                    "shopping.shopify.enabled requires shopping.provider=shopify"
                            );
                });
    }

    @Test
    void usesShopifyProviderWhenExplicitlyEnabled() {
        contextRunner
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "shopping.provider=shopify",
                        "shopping.shopify.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductCatalogPort.class);
                    assertThat(context).hasSingleBean(ShopifyGlobalCatalogClient.class);
                    assertThat(context).hasSingleBean(ShopifyGlobalCatalogAdapter.class);
                    assertThat(context).doesNotHaveBean(FixtureShoppingProviderAdapter.class);
                });
    }

    @Test
    void rejectsNonPositiveCandidateTokenTtl() {
        contextRunner
                .withPropertyValues("shopping.candidate-token.ttl=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "shopping.candidate-token.ttl must be positive"
                            );
                });
    }
}
