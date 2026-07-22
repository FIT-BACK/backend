package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageSignatureValidatorTest {

    private final ImageSignatureValidator validator = new ImageSignatureValidator();

    @Test
    void recognizesAllowedImageSignatures() {
        assertThat(validator.matches(
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        )).isTrue();
        assertThat(validator.matches(
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}
        )).isTrue();
        assertThat(validator.matches(
                "image/webp",
                "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII)
        )).isTrue();
    }

    @Test
    void rejectsContentWhoseSignatureDoesNotMatchMimeType() {
        assertThat(validator.matches(
                "image/png",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        )).isFalse();
    }
}
