package com.fitback.backend.external.storage.s3;

import com.fitback.backend.domain.image.service.port.ImageUploadUrl;
import com.fitback.backend.domain.image.service.port.ImageUploadUrlPort;
import com.fitback.backend.global.config.ImageStorageProperties;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class S3ImageUploadUrlAdapter implements ImageUploadUrlPort {

    private final S3Presigner s3Presigner;
    private final ImageStorageProperties properties;

    @Override
    public ImageUploadUrl create(
            String objectKey,
            String contentType,
            Duration expiration
    ) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .putObjectRequest(putObjectRequest)
                .build();
        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);

        return new ImageUploadUrl(
                presignedRequest.url().toExternalForm(),
                presignedRequest.httpRequest().method().name(),
                Map.of("Content-Type", contentType),
                properties.cdnBaseUrl() + "/" + objectKey
        );
    }
}
