package com.fitback.backend.domain.image.service;

import java.time.Instant;
import java.util.Map;

public interface ImageObjectStorage {

    PresignedPost createPresignedPost(
            String storageKey,
            String mimeType,
            long fileSizeBytes,
            Instant expiresAt
    );

    StoredImageObject inspect(String storageKey);

    void delete(String storageKey);

    record PresignedPost(
            String uploadUrl,
            Map<String, String> uploadFields,
            Instant expiresAt
    ) {
    }

    record StoredImageObject(
            long fileSizeBytes,
            String mimeType,
            byte[] signatureBytes
    ) {
        public StoredImageObject {
            signatureBytes = signatureBytes.clone();
        }

        @Override
        public byte[] signatureBytes() {
            return signatureBytes.clone();
        }
    }
}
