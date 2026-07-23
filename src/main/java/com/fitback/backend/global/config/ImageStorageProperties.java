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

    public ImageStorageProperties {
        awsRegion = requireText(awsRegion, "image.storage.aws-region");
        bucket = requireText(bucket, "image.storage.bucket");
        cdnBaseUrl = normalizeCdnBaseUrl(cdnBaseUrl);
        cloudfrontKeyPairId = requireText(
                cloudfrontKeyPairId,
                "image.storage.cloudfront-key-pair-id"
        );
        cloudfrontPrivateKeyBase64 = requireText(
                cloudfrontPrivateKeyBase64,
                "image.storage.cloudfront-private-key-base64"
        );
    }

    @Override
    public String toString() {
        return ("ImageStorageProperties[awsRegion=%s, bucket=%s, cdnBaseUrl=%s, "
                + "cloudfrontKeyPairId=%s, cloudfrontPrivateKeyBase64=****]")
                .formatted(awsRegion, bucket, cdnBaseUrl, cloudfrontKeyPairId);
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value;
    }

    private static String normalizeCdnBaseUrl(String value) {
        String normalized = requireText(value, "image.storage.cdn-base-url");
        return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
