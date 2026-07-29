package com.fitback.backend.external.shopping.config;

import com.fitback.backend.domain.product.service.port.ProductCategoryMapper;
import com.fitback.backend.external.shopping.fixture.FixtureProductCategoryMapper;
import com.fitback.backend.external.shopping.fixture.FixtureShoppingProviderAdapter;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogAdapter;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogClient;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogHttpClient;
import com.fitback.backend.external.shopping.shopify.ShopifyProductCategoryMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShoppingProviderProperties.class)
public class ShoppingProviderConfig {

    @Bean
    @ConditionalOnProperty(
            name = "shopping.provider",
            havingValue = "fixture",
            matchIfMissing = true
    )
    public FixtureShoppingProviderAdapter fixtureShoppingProviderAdapter() {
        return new FixtureShoppingProviderAdapter();
    }

    @Bean
    @ConditionalOnProperty(
            name = "shopping.provider",
            havingValue = "fixture",
            matchIfMissing = true
    )
    public ProductCategoryMapper fixtureProductCategoryMapper() {
        return new FixtureProductCategoryMapper();
    }

    @Bean
    @ConditionalOnProperty(name = "shopping.provider", havingValue = "shopify")
    public ShopifyGlobalCatalogClient shopifyGlobalCatalogClient(
            ShoppingProviderProperties properties,
            ObjectMapper objectMapper
    ) {
        return new ShopifyGlobalCatalogHttpClient(properties.shopify(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "shopping.provider", havingValue = "shopify")
    public ShopifyProductCategoryMapper shopifyProductCategoryMapper() {
        return new ShopifyProductCategoryMapper();
    }

    @Bean
    @ConditionalOnProperty(name = "shopping.provider", havingValue = "shopify")
    public ShopifyGlobalCatalogAdapter shopifyGlobalCatalogAdapter(
            ShopifyGlobalCatalogClient client,
            ShopifyProductCategoryMapper categoryMapper,
            ShoppingProviderProperties properties,
            Clock clock
    ) {
        return new ShopifyGlobalCatalogAdapter(
                client,
                categoryMapper,
                properties.shopify(),
                clock
        );
    }
}
