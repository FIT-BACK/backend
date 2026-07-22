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
                    "DB_PASSWORD=secret",
                    "JWT_SECRET_KEY=production-jwt-secret-key-at-least-32-bytes",
                    "AWS_REGION=ap-northeast-2",
                    "IMAGE_BUCKET=fitback-prod-images",
                    "IMAGE_CDN_BASE_URL=https://images.example.com",
                    "CLOUDFRONT_KEY_PAIR_ID=TESTKEY",
                    "CLOUDFRONT_PRIVATE_KEY_BASE64=dGVzdC1rZXk="
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
            assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
            assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class))
                    .isTrue();
            assertThat(environment.getProperty("spring.flyway.baseline-version"))
                    .isEqualTo("0");
            assertThat(environment.getProperty("jwt.token.secretKey"))
                    .isEqualTo("production-jwt-secret-key-at-least-32-bytes");
            assertThat(environment.getProperty("image.storage.aws-region"))
                    .isEqualTo("ap-northeast-2");
            assertThat(environment.getProperty("image.storage.bucket"))
                    .isEqualTo("fitback-prod-images");
            assertThat(environment.getProperty("image.storage.cdn-base-url"))
                    .isEqualTo("https://images.example.com");
            assertThat(environment.getProperty("image.storage.cloudfront-key-pair-id"))
                    .isEqualTo("TESTKEY");
            assertThat(environment.getProperty("image.storage.cloudfront-private-key-base64"))
                    .isEqualTo("dGVzdC1rZXk=");
        });
    }
}
