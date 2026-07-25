package com.fitback.backend.external.storage.s3;

import com.fitback.backend.domain.image.service.port.ImageUploadUrl;
import com.fitback.backend.domain.image.service.port.ImageUploadUrlPort;
import com.fitback.backend.global.config.ImageStorageProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class S3ImageUploadUrlAdapter implements ImageUploadUrlPort {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String SUCCESS_STATUS = "204";
    private static final Duration MAX_EXPIRATION = Duration.ofDays(7);
    private static final DateTimeFormatter AMZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd")
                    .withZone(ZoneOffset.UTC);

    private final AwsCredentialsProvider credentialsProvider;
    private final ImageStorageProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Override
    public ImageUploadUrl create(
            String objectKey,
            String contentType,
            long fileSize,
            Instant expiresAt
    ) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        validateExpiration(issuedAt, expiresAt);

        AwsCredentials credentials = credentialsProvider.resolveCredentials();
        String dateStamp = DATE_STAMP_FORMATTER.format(issuedAt);
        String amzDate = AMZ_DATE_FORMATTER.format(issuedAt);
        String credentialScope = "%s/%s/%s/%s".formatted(
                dateStamp,
                properties.awsRegion(),
                SERVICE,
                TERMINATOR
        );
        String credential = credentials.accessKeyId() + "/" + credentialScope;

        Map<String, String> uploadFields = createUploadFields(
                objectKey,
                contentType,
                credentials,
                credential,
                amzDate
        );
        String policy = createPolicy(
                objectKey,
                contentType,
                fileSize,
                expiresAt,
                uploadFields
        );
        uploadFields.put("policy", policy);
        uploadFields.put(
                "x-amz-signature",
                calculateSignature(
                        policy,
                        credentials.secretAccessKey(),
                        dateStamp,
                        properties.awsRegion()
                )
        );

        return new ImageUploadUrl(
                createUploadUrl(),
                "POST",
                uploadFields
        );
    }

    private Map<String, String> createUploadFields(
            String objectKey,
            String contentType,
            AwsCredentials credentials,
            String credential,
            String amzDate
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", objectKey);
        fields.put("Content-Type", contentType);
        fields.put("success_action_status", SUCCESS_STATUS);
        fields.put("x-amz-algorithm", ALGORITHM);
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", amzDate);
        if (credentials instanceof AwsSessionCredentials sessionCredentials) {
            fields.put("x-amz-security-token", sessionCredentials.sessionToken());
        }
        return fields;
    }

    private String createPolicy(
            String objectKey,
            String contentType,
            long fileSize,
            Instant expiresAt,
            Map<String, String> uploadFields
    ) {
        List<Object> conditions = new ArrayList<>();
        conditions.add(Map.of("bucket", properties.bucket()));
        conditions.add(Map.of("key", objectKey));
        conditions.add(Map.of("Content-Type", contentType));
        conditions.add(Map.of("success_action_status", SUCCESS_STATUS));
        conditions.add(Map.of("x-amz-algorithm", uploadFields.get("x-amz-algorithm")));
        conditions.add(Map.of("x-amz-credential", uploadFields.get("x-amz-credential")));
        conditions.add(Map.of("x-amz-date", uploadFields.get("x-amz-date")));
        String securityToken = uploadFields.get("x-amz-security-token");
        if (securityToken != null) {
            conditions.add(Map.of("x-amz-security-token", securityToken));
        }
        conditions.add(List.of("content-length-range", fileSize, fileSize));

        Map<String, Object> policyDocument = new LinkedHashMap<>();
        policyDocument.put(
                "expiration",
                expiresAt.truncatedTo(ChronoUnit.SECONDS).toString()
        );
        policyDocument.put("conditions", conditions);

        try {
            byte[] policyBytes = objectMapper.writeValueAsBytes(policyDocument);
            return Base64.getEncoder().encodeToString(policyBytes);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to serialize S3 POST policy", exception);
        }
    }

    static String calculateSignature(
            String policy,
            String secretAccessKey,
            String dateStamp,
            String region
    ) {
        byte[] dateKey = hmac(
                dateStamp,
                ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8)
        );
        byte[] regionKey = hmac(region, dateKey);
        byte[] serviceKey = hmac(SERVICE, regionKey);
        byte[] signingKey = hmac(TERMINATOR, serviceKey);
        return HexFormat.of().formatHex(hmac(policy, signingKey));
    }

    private static byte[] hmac(String data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to sign S3 POST policy", exception);
        }
    }

    private String createUploadUrl() {
        return "https://%s.s3.%s.amazonaws.com/".formatted(
                properties.bucket(),
                properties.awsRegion()
        );
    }

    private void validateExpiration(Instant issuedAt, Instant expiresAt) {
        if (!expiresAt.isAfter(issuedAt)
                || expiresAt.isAfter(issuedAt.plus(MAX_EXPIRATION))) {
            throw new IllegalArgumentException("S3 POST expiration must be within seven days");
        }
    }
}
