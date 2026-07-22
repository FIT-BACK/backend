package com.fitback.backend.external.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.image.service.port.ImageUploadUrl;
import com.fitback.backend.global.config.ImageStorageProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ImageUploadUrlAdapterTest {

    @Test
    void createsPutUrlForConfiguredPrivateBucket() {
        ImageStorageProperties properties = new ImageStorageProperties(
                "ap-northeast-2",
                "fitback-test-images",
                "https://cdn.example/",
                "TESTKEY",
                "dGVzdC1wcml2YXRlLWtleQ=="
        );
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")
                ))
                .build()) {
            S3ImageUploadUrlAdapter adapter = new S3ImageUploadUrlAdapter(
                    presigner,
                    properties
            );

            ImageUploadUrl result = adapter.create(
                    "prod/images/profile/2026/07/image-id.jpg",
                    "image/jpeg",
                    1024,
                    Duration.ofMinutes(5)
            );

            assertThat(result.uploadMethod()).isEqualTo("PUT");
            assertThat(result.uploadUrl())
                    .startsWith("https://fitback-test-images.s3.ap-northeast-2.amazonaws.com/")
                    .contains("X-Amz-Expires=300")
                    .contains("X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost")
                    .doesNotContain("test-secret-key");
            assertThat(result.requiredHeaders()).containsEntry("Content-Type", "image/jpeg");
            assertThat(result.imageUrl()).isEqualTo(
                    "https://cdn.example/prod/images/profile/2026/07/image-id.jpg"
            );
        }
    }
}
