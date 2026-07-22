package com.fitback.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image.storage")
public record ImageStorageProperties(
        String awsRegion,
        String bucket,
        String cdnBaseUrl,
        String cloudfrontKeyPairId,
        String cloudfrontPrivateKeyBase64
) {
}
