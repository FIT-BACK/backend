package com.fitback.backend.domain.image.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ImageUploadRequest(
        @NotNull ImageUploadPurpose purpose,
        @NotBlank String contentType,
        @Positive @Max(5_242_880) long fileSize
) {
}
