package com.fitback.backend.domain.image.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.service.ImageObjectStorage.StoredImageObject;
import com.fitback.backend.global.config.ImageStorageProperties;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import org.junit.jupiter.api.Test;

class AwsS3ImageObjectStorageTest {

    @Test
    void inspectsStoredObjectMetadataAndSignature() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(1024L)
                        .contentType("image/jpeg")
                        .build()
        );
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(),
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
                )
        );
        AwsS3ImageObjectStorage storage = new AwsS3ImageObjectStorage(
                s3Client,
                new ImageStorageProperties(
                        "ap-northeast-2",
                        "fitback-test-images",
                        "https://cdn.example.com",
                        null,
                        null
                )
        );

        StoredImageObject result = storage.inspect(
                "prod/images/analysis_original/2026/07/image.jpg"
        );

        assertThat(result.fileSizeBytes()).isEqualTo(1024L);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.signatureBytes()).containsExactly(
                (byte) 0xFF,
                (byte) 0xD8,
                (byte) 0xFF
        );
    }
}
