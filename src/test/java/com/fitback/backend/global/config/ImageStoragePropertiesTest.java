package com.fitback.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void rejectsBlankCloudFrontSigningConfiguration() {
        assertThatThrownBy(() -> new ImageStorageProperties(
                "ap-northeast-2",
                "fitback-images",
                "https://images.example.com",
                " ",
                "private-key"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cloudfront-key-pair-id");

        assertThatThrownBy(() -> new ImageStorageProperties(
                "ap-northeast-2",
                "fitback-images",
                "https://images.example.com",
                "TESTKEY",
                " "
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cloudfront-private-key-base64");
    }
}
