package com.fitback.backend.external.aitag.s3;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.external.aitag.AiTagImage;
import com.fitback.backend.external.aitag.AnalysisImageContentLoader;
import com.fitback.backend.global.config.ImageStorageProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

public final class S3AnalysisImageContentLoader implements AnalysisImageContentLoader {

    private final S3Client s3Client;
    private final ImageStorageProperties properties;

    public S3AnalysisImageContentLoader(
            S3Client s3Client,
            ImageStorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public AiTagImage load(Image image) {
        try {
            byte[] bytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(image.getObjectKey())
                    .build()).asByteArray();
            return new AiTagImage(bytes, image.getContentType());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
        }
    }
}
