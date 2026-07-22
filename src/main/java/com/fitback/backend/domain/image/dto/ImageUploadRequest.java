package com.fitback.backend.domain.image.dto;

import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ImageUploadRequest(
        @NotNull ImagePurpose purpose,
        @NotBlank String contentType,
        @Positive
        @Max(ImageAsset.MAX_FILE_SIZE_BYTES)
        long fileSize
) {
}
