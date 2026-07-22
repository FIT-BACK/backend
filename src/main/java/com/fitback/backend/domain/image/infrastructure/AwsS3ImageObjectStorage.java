package com.fitback.backend.domain.image.infrastructure;

import com.fitback.backend.domain.image.service.ImageObjectStorage;
import com.fitback.backend.global.config.ImageStorageProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

@Component
public class AwsS3ImageObjectStorage implements ImageObjectStorage {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final S3Client s3Client;
    private final AwsCredentialsProvider credentialsProvider;
    private final ImageStorageProperties properties;

    public AwsS3ImageObjectStorage(
            @Lazy S3Client s3Client,
            AwsCredentialsProvider credentialsProvider,
            ImageStorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.credentialsProvider = credentialsProvider;
        this.properties = properties;
    }

    @Override
    public PresignedPost createPresignedPost(
            String storageKey,
            String mimeType,
            long fileSizeBytes,
            Instant expiresAt
    ) {
        try {
            AwsCredentials credentials = credentialsProvider.resolveCredentials();
            Instant now = Instant.now();
            String date = DATE.format(now);
            String amzDate = AMZ_DATE.format(now);
            String credential = "%s/%s/%s/s3/aws4_request".formatted(
                    credentials.accessKeyId(),
                    date,
                    properties.awsRegion()
            );
            String policy = createPolicy(
                    storageKey,
                    mimeType,
                    fileSizeBytes,
                    expiresAt,
                    credential,
                    amzDate,
                    credentials
            );
            String encodedPolicy = Base64.getEncoder().encodeToString(
                    policy.getBytes(StandardCharsets.UTF_8)
            );

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("key", storageKey);
            fields.put("Content-Type", mimeType);
            fields.put("x-amz-algorithm", ALGORITHM);
            fields.put("x-amz-credential", credential);
            fields.put("x-amz-date", amzDate);
            if (credentials instanceof AwsSessionCredentials sessionCredentials) {
                fields.put("x-amz-security-token", sessionCredentials.sessionToken());
            }
            fields.put("policy", encodedPolicy);
            fields.put("x-amz-signature", signPolicy(credentials.secretAccessKey(), date, encodedPolicy));

            return new PresignedPost(uploadUrl(), fields, expiresAt);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.IMAGE_PRESIGN_ERROR);
        }
    }

    @Override
    public StoredImageObject inspect(String storageKey) {
        try {
            HeadObjectResponse head = s3Client.headObject(builder -> builder
                    .bucket(properties.bucket())
                    .key(storageKey));
            ResponseBytes<GetObjectResponse> signature = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(storageKey)
                            .range("bytes=0-11")
                            .build()
            );
            return new StoredImageObject(
                    head.contentLength(),
                    head.contentType(),
                    signature.asByteArray()
            );
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3Client.deleteObject(builder -> builder
                    .bucket(properties.bucket())
                    .key(storageKey));
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        }
    }

    private String createPolicy(
            String storageKey,
            String mimeType,
            long fileSizeBytes,
            Instant expiresAt,
            String credential,
            String amzDate,
            AwsCredentials credentials
    ) {
        String sessionCondition = credentials instanceof AwsSessionCredentials sessionCredentials
                ? ",{\"x-amz-security-token\":\"" + sessionCredentials.sessionToken() + "\"}"
                : "";
        return """
                {"expiration":"%s","conditions":[
                  {"bucket":"%s"},
                  {"key":"%s"},
                  {"Content-Type":"%s"},
                  {"x-amz-algorithm":"%s"},
                  {"x-amz-credential":"%s"},
                  {"x-amz-date":"%s"}%s,
                  ["content-length-range",1,%d]
                ]}
                """.formatted(
                expiresAt,
                properties.bucket(),
                storageKey,
                mimeType,
                ALGORITHM,
                credential,
                amzDate,
                sessionCondition,
                fileSizeBytes
        ).replaceAll("\\s+", "");
    }

    private String signPolicy(String secretKey, String date, String encodedPolicy) {
        try {
            byte[] dateKey = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] regionKey = hmac(dateKey, properties.awsRegion());
            byte[] serviceKey = hmac(regionKey, "s3");
            byte[] signingKey = hmac(serviceKey, "aws4_request");
            return java.util.HexFormat.of().formatHex(hmac(signingKey, encodedPolicy));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Unable to sign S3 POST policy", exception);
        }
    }

    private byte[] hmac(byte[] key, String value)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String uploadUrl() {
        return "https://%s.s3.%s.amazonaws.com".formatted(
                properties.bucket(),
                properties.awsRegion()
        );
    }
}
