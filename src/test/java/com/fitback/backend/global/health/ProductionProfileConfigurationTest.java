package com.fitback.backend.global.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class ProductionProfileConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "DB_URL=jdbc:mysql://production-db:3306/fitback",
                    "DB_USER=fitback",
                    "DB_PASSWORD=secret"
            );

    @Test
    void productionProfileMapsRequiredDatabaseEnvironmentVariables() {
        contextRunner.run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getActiveProfiles()).containsExactly("prod");
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:mysql://production-db:3306/fitback");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("fitback");
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("secret");
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        });
    }
}
