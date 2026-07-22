package com.fitback.backend.domain.image.infrastructure;

import com.fitback.backend.domain.image.service.ImageObjectStorage;
import com.fitback.backend.global.config.ImageStorageProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

@Component
@RequiredArgsConstructor
public class AwsS3ImageObjectStorage implements ImageObjectStorage {

    private static final String SIGNATURE_BYTE_RANGE = "bytes=0-11";

    private final S3Client s3Client;
    private final ImageStorageProperties properties;

    @Override
    public StoredImageObject inspect(String objectKey) {
        try {
            HeadObjectResponse metadata = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
            ResponseBytes<GetObjectResponse> signature = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .range(SIGNATURE_BYTE_RANGE)
                            .build()
            );
            return new StoredImageObject(
                    metadata.contentLength(),
                    metadata.contentType(),
                    signature.asByteArray()
            );
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        }
    }
}
