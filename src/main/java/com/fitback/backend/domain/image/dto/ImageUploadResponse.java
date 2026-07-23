package com.fitback.backend.domain.image.dto;

import java.time.Instant;
import java.util.Map;

public record ImageUploadResponse(
        String imageId,
        String uploadUrl,
        String uploadMethod,
        Map<String, String> requiredHeaders,
        Instant expiresAt,
        String imageUrl
) {
}
