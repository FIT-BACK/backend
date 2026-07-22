package com.fitback.backend.domain.image.service.port;

import java.util.Map;

public record ImageUploadUrl(
        String uploadUrl,
        String uploadMethod,
        Map<String, String> requiredHeaders,
        String imageUrl
) {
}
