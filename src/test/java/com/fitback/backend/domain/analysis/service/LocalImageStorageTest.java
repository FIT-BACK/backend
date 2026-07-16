package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalImageStorageTest {

    @TempDir
    Path uploadDirectory;

    @Test
    void storesImageWithGeneratedNameAfterSignatureValidation() {
        LocalImageStorage imageStorage = new LocalImageStorage(uploadDirectory.toString());
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "look.png",
                "image/png",
                new byte[]{
                    (byte) 0x89, 0x50, 0x4E, 0x47,
                    0x0D, 0x0A, 0x1A, 0x0A
                }
        );

        String imageUrl = imageStorage.store(image);

        assertThat(imageUrl).startsWith("/uploads/").endsWith(".png");
        assertThat(uploadDirectory.resolve(imageUrl.substring("/uploads/".length())))
                .isRegularFile();
    }

    @Test
    void rejectsFileWhoseBytesDoNotMatchContentType() {
        LocalImageStorage imageStorage = new LocalImageStorage(uploadDirectory.toString());
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "fake.jpg",
                "image/jpeg",
                "not-an-image".getBytes()
        );

        assertThatThrownBy(() -> imageStorage.store(image))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ANALYSIS_IMAGE);
    }
}
