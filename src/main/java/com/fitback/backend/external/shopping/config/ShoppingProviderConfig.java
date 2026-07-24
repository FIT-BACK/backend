package com.fitback.backend.external.shopping.config;

import com.fitback.backend.external.shopping.fixture.FixtureShoppingProviderAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShoppingProviderProperties.class)
public class ShoppingProviderConfig {

    @Bean
    public FixtureShoppingProviderAdapter fixtureShoppingProviderAdapter(
            ShoppingProviderProperties properties
    ) {
        if (properties.provider() != ShoppingProviderProperties.Provider.FIXTURE) {
            throw new IllegalStateException(
                    "Shopify runtime provider is unavailable while selection remains pending"
            );
        }
        return new FixtureShoppingProviderAdapter();
    }
}
