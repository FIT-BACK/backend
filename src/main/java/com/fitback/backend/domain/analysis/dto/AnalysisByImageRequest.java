package com.fitback.backend.domain.analysis.dto;

import jakarta.validation.constraints.NotBlank;

public record AnalysisByImageRequest(
        @NotBlank(message = "imageId는 필수입니다.")
        String imageId
) {
}
