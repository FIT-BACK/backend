package com.fitback.backend.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "image.storage")
public record ImageStorageProperties(
        String awsRegion,
        String bucket,
        String cdnBaseUrl,
        String cloudfrontKeyPairId,
        String cloudfrontPrivateKeyBase64,
        Duration apiCallTimeout,
        Duration apiCallAttemptTimeout
) {

    public ImageStorageProperties(
            String awsRegion,
            String bucket,
            String cdnBaseUrl,
            String cloudfrontKeyPairId,
            String cloudfrontPrivateKeyBase64
    ) {
        this(
                awsRegion,
                bucket,
                cdnBaseUrl,
                cloudfrontKeyPairId,
                cloudfrontPrivateKeyBase64,
                null,
                null
        );
    }

    @ConstructorBinding
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
        apiCallTimeout = positiveOrDefault(
                apiCallTimeout,
                Duration.ofSeconds(5),
                "image.storage.api-call-timeout"
        );
        apiCallAttemptTimeout = positiveOrDefault(
                apiCallAttemptTimeout,
                Duration.ofSeconds(2),
                "image.storage.api-call-attempt-timeout"
        );
        if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalArgumentException(
                    "image.storage.api-call-attempt-timeout must not exceed api-call-timeout"
            );
        }
    }

    @Override
    public String toString() {
        return ("ImageStorageProperties[awsRegion=%s, bucket=%s, cdnBaseUrl=%s, "
                + "cloudfrontKeyPairId=%s, cloudfrontPrivateKeyBase64=****, "
                + "apiCallTimeout=%s, apiCallAttemptTimeout=%s]")
                .formatted(
                        awsRegion,
                        bucket,
                        cdnBaseUrl,
                        cloudfrontKeyPairId,
                        apiCallTimeout,
                        apiCallAttemptTimeout
                );
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

    private static Duration positiveOrDefault(
            Duration value,
            Duration defaultValue,
            String propertyName
    ) {
        Duration resolved = value == null ? defaultValue : value;
        if (resolved.isNegative() || resolved.isZero()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return resolved;
    }
}
