package com.fitback.backend.domain.image.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fitback.backend.domain.image.service.ImageObjectStorage.PresignedPost;
import com.fitback.backend.global.config.ImageStorageProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

class AwsS3ImageObjectStorageTest {

    @Test
    void presignedPostPolicyRestrictsKeyTypeAndSize() {
        AwsS3ImageObjectStorage storage = new AwsS3ImageObjectStorage(
                mock(S3Client.class),
                StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")),
                new ImageStorageProperties(
                        "ap-northeast-2",
                        "fitback-test-images",
                        "https://cdn.example.com",
                        "key-pair",
                        "private-key"
                )
        );
        Instant expiresAt = Instant.parse("2026-07-22T00:05:00Z");

        PresignedPost result = storage.createPresignedPost(
                "images/analysis/1/2026/07/image.jpg",
                "image/jpeg",
                1024,
                expiresAt
        );
        String policy = new String(
                Base64.getDecoder().decode(result.uploadFields().get("policy")),
                StandardCharsets.UTF_8
        );

        assertThat(result.uploadUrl())
                .isEqualTo("https://fitback-test-images.s3.ap-northeast-2.amazonaws.com");
        assertThat(result.uploadFields())
                .containsEntry("key", "images/analysis/1/2026/07/image.jpg")
                .containsEntry("Content-Type", "image/jpeg")
                .containsKeys("policy", "x-amz-signature", "x-amz-credential");
        assertThat(policy)
                .contains("[\"content-length-range\",1,1024]")
                .contains("{\"Content-Type\":\"image/jpeg\"}")
                .contains("{\"key\":\"images/analysis/1/2026/07/image.jpg\"}");
    }
}
