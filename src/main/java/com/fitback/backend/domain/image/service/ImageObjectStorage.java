package com.fitback.backend.domain.image.service;

public interface ImageObjectStorage {

    StoredImageObject inspect(String objectKey);

    void delete(String objectKey);

    record StoredImageObject(
            long fileSizeBytes,
            String contentType,
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
