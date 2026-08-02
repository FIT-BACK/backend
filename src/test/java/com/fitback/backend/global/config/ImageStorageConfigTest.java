package com.fitback.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

class ImageStorageConfigTest {

    @Test
    void configuresS3ApiCallAndAttemptTimeouts() {
        ClientOverrideConfiguration configuration =
                new ImageStorageConfig().imageS3ClientOverrideConfiguration(
                        new ImageStorageProperties(
                                "ap-northeast-2",
                                "fitback-test-images",
                                "https://cdn.example.com",
                                "TESTKEY",
                                "dGVzdC1wcml2YXRlLWtleQ=="
                        )
                );

        assertThat(configuration.apiCallTimeout()).contains(Duration.ofSeconds(5));
        assertThat(configuration.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
    }
}
