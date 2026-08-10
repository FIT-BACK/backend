package com.fitback.backend.external.fashionclip;

import java.util.Locale;
import java.util.Set;

public record FashionClipImageInput(byte[] imageBytes, String contentType) {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    public FashionClipImageInput {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must not be null or empty");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be null or blank");
        }
        contentType = contentType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("contentType must be image/jpeg, image/png, or image/webp");
        }
        imageBytes = imageBytes.clone();
    }

    @Override
    public byte[] imageBytes() {
        return imageBytes.clone();
    }
}
