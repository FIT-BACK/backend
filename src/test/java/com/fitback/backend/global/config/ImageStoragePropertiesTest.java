package com.fitback.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageStoragePropertiesTest {

    @Test
    void masksCloudFrontPrivateKeyInStringRepresentation() {
        ImageStorageProperties properties = new ImageStorageProperties(
                "ap-northeast-2",
                "fitback-images",
                "https://images.example.com/",
                "KEYPAIR",
                "private-key-value"
        );

        assertThat(properties.toString())
                .contains("fitback-images", "KEYPAIR", "cloudfrontPrivateKeyBase64=****")
                .doesNotContain("private-key-value");
    }
}
