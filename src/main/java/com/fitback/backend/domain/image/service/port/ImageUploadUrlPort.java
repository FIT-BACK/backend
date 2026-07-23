package com.fitback.backend.domain.image.service.port;

import java.time.Duration;

public interface ImageUploadUrlPort {

    ImageUploadUrl create(
            String objectKey,
            String contentType,
            long fileSize,
            Duration expiration
    );
}
