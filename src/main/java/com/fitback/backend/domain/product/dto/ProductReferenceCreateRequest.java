package com.fitback.backend.domain.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductReferenceCreateRequest(
        @NotBlank
        @Size(max = 4096)
        String candidateToken
) {
}
