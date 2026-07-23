package com.fitback.backend.domain.image.service.port;

import java.time.Instant;

public interface ImageUploadUrlPort {

    ImageUploadUrl create(
            String objectKey,
            String contentType,
            long fileSize,
            Instant expiresAt
    );
}
