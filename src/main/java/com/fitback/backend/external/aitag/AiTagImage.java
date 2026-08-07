package com.fitback.backend.external.aitag;

import java.util.Arrays;
import java.util.Locale;

public record AiTagImage(byte[] bytes, String contentType) {

    public AiTagImage {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("image bytes must not be empty");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("image content type must not be blank");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
        contentType = contentType.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
