package com.fitback.backend.external.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.image.service.port.ImageUploadUrl;
import com.fitback.backend.global.config.ImageStorageProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class S3ImageUploadUrlAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-24T00:05:00Z");
    private static final String OBJECT_KEY =
            "images/profile/42/2026/07/image-id.jpg";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AWS_EXAMPLE_POLICY = """
            eyAiZXhwaXJhdGlvbiI6ICIyMDE1LTEyLTMwVDEyOjAwOjAwLjAwMFoiLA0KICAiY29uZGl0aW9ucyI6IFsNCiAgICB7ImJ1Y2tl
            dCI6ICJzaWd2NGV4YW1wbGVidWNrZXQifSwNCiAgICBbInN0YXJ0cy13aXRoIiwgIiRrZXkiLCAidXNlci91c2VyMS8iXSwNCiAg
            ICB7ImFjbCI6ICJwdWJsaWMtcmVhZCJ9LA0KICAgIHsic3VjY2Vzc19hY3Rpb25fcmVkaXJlY3QiOiAiaHR0cDovL3NpZ3Y0ZXhh
            bXBsZWJ1Y2tldC5zMy5hbWF6b25hd3MuY29tL3N1Y2Nlc3NmdWxfdXBsb2FkLmh0bWwifSwNCiAgICBbInN0YXJ0cy13aXRoIiwg
            IiRDb250ZW50LVR5cGUiLCAiaW1hZ2UvIl0sDQogICAgeyJ4LWFtei1tZXRhLXV1aWQiOiAiMTQzNjUxMjM2NTEyNzQifSwNCiAg
            ICB7IngtYW16LXNlcnZlci1zaWRlLWVuY3J5cHRpb24iOiAiQUVTMjU2In0sDQogICAgWyJzdGFydHMtd2l0aCIsICIkeC1hbXot
            bWV0YS10YWciLCAiIl0sDQoNCiAgICB7IngtYW16LWNyZWRlbnRpYWwiOiAiQUtJQUlPU0ZPRE5ON0VYQU1QTEUvMjAxNTEyMjkv
            dXMtZWFzdC0xL3MzL2F3czRfcmVxdWVzdCJ9LA0KICAgIHsieC1hbXotYWxnb3JpdGhtIjogIkFXUzQtSE1BQy1TSEEyNTYifSwN
            CiAgICB7IngtYW16LWRhdGUiOiAiMjAxNTEyMjlUMDAwMDAwWiIgfQ0KICBdDQp9
            """.replace("\n", "");

    @Test
    void createsPostPolicyForConfiguredPrivateBucket() throws Exception {
        AtomicInteger resolutionCount = new AtomicInteger();
        AwsCredentialsProvider credentialsProvider = () -> {
            resolutionCount.incrementAndGet();
            return AwsBasicCredentials.create("test-access-key", "test-secret-key");
        };
        S3ImageUploadUrlAdapter adapter = adapterWith(credentialsProvider);

        ImageUploadUrl result = adapter.create(
                OBJECT_KEY,
                "image/jpeg",
                1024,
                EXPIRES_AT
        );

        assertThat(result.uploadMethod()).isEqualTo("POST");
        assertThat(result.uploadUrl())
                .isEqualTo("https://fitback-test-images.s3.ap-northeast-2.amazonaws.com/")
                .doesNotContain(OBJECT_KEY)
                .doesNotContain("test-secret-key");
        assertThat(result.uploadFields())
                .containsEntry("key", OBJECT_KEY)
                .containsEntry("Content-Type", "image/jpeg")
                .containsEntry("success_action_status", "204")
                .containsEntry("x-amz-algorithm", "AWS4-HMAC-SHA256")
                .containsEntry(
                        "x-amz-credential",
                        "test-access-key/20260724/ap-northeast-2/s3/aws4_request"
                )
                .containsEntry("x-amz-date", "20260724T000000Z")
                .doesNotContainKey("x-amz-security-token");
        assertThat(result.uploadFields().keySet()).containsExactlyInAnyOrder(
                "key",
                "Content-Type",
                "success_action_status",
                "x-amz-algorithm",
                "x-amz-credential",
                "x-amz-date",
                "policy",
                "x-amz-signature"
        );
        assertThat(result.uploadFields().get("x-amz-signature"))
                .matches("[0-9a-f]{64}");
        assertThat(String.join("", result.uploadFields().values()))
                .doesNotContain("test-secret-key");
        assertThat(resolutionCount).hasValue(1);

        JsonNode policy = decodePolicy(result.uploadFields().get("policy"));
        assertThat(policy.get("expiration").stringValue())
                .isEqualTo("2026-07-24T00:05:00Z");
        JsonNode conditions = policy.get("conditions");
        assertThat(hasCondition(conditions, "bucket", "fitback-test-images")).isTrue();
        assertThat(hasCondition(conditions, "key", OBJECT_KEY)).isTrue();
        assertThat(hasCondition(conditions, "Content-Type", "image/jpeg")).isTrue();
        assertThat(hasCondition(conditions, "success_action_status", "204")).isTrue();
        assertThat(hasCondition(
                conditions,
                "x-amz-algorithm",
                "AWS4-HMAC-SHA256"
        )).isTrue();
        assertThat(hasCondition(
                conditions,
                "x-amz-credential",
                "test-access-key/20260724/ap-northeast-2/s3/aws4_request"
        )).isTrue();
        assertThat(hasCondition(
                conditions,
                "x-amz-date",
                "20260724T000000Z"
        )).isTrue();
        assertThat(hasContentLengthRange(conditions, 1024)).isTrue();
    }

    @Test
    void matchesAwsPublishedSignatureExample() {
        String signature = S3ImageUploadUrlAdapter.calculateSignature(
                AWS_EXAMPLE_POLICY,
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "20151229",
                "us-east-1"
        );

        assertThat(signature)
                .isEqualTo("8afdbf4008c03f22c2cd3cdb72e4afbb1f6a588f3255ac628749a66d7f09699e");
    }

    @Test
    void includesSessionTokenInFieldsAndPolicy() throws Exception {
        S3ImageUploadUrlAdapter adapter = adapterWith(
                StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(
                                "session-access-key",
                                "session-secret-key",
                                "test-session-token"
                        )
                )
        );

        ImageUploadUrl result = adapter.create(
                OBJECT_KEY,
                "image/png",
                2048,
                EXPIRES_AT
        );

        assertThat(result.uploadFields())
                .containsEntry("x-amz-security-token", "test-session-token");
        JsonNode conditions = decodePolicy(result.uploadFields().get("policy"));
        assertThat(hasCondition(
                conditions.get("conditions"),
                "x-amz-security-token",
                "test-session-token"
        )).isTrue();
        assertThat(hasContentLengthRange(conditions.get("conditions"), 2048)).isTrue();
    }

    private S3ImageUploadUrlAdapter adapterWith(
            AwsCredentialsProvider credentialsProvider
    ) {
        ImageStorageProperties properties = new ImageStorageProperties(
                "ap-northeast-2",
                "fitback-test-images",
                "https://cdn.example/",
                "TESTKEY",
                "dGVzdC1wcml2YXRlLWtleQ=="
        );
        return new S3ImageUploadUrlAdapter(
                credentialsProvider,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                OBJECT_MAPPER
        );
    }

    private JsonNode decodePolicy(String encodedPolicy) throws Exception {
        byte[] policyBytes = Base64.getDecoder().decode(encodedPolicy);
        return OBJECT_MAPPER.readTree(new String(policyBytes, StandardCharsets.UTF_8));
    }

    private boolean hasCondition(
            JsonNode conditions,
            String field,
            String expectedValue
    ) {
        for (JsonNode condition : conditions) {
            if (condition.isObject()
                    && condition.has(field)
                    && expectedValue.equals(condition.get(field).stringValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasContentLengthRange(JsonNode conditions, long expectedSize) {
        for (JsonNode condition : conditions) {
            if (condition.isArray()
                    && condition.size() == 3
                    && "content-length-range".equals(condition.get(0).stringValue())
                    && condition.get(1).longValue() == expectedSize
                    && condition.get(2).longValue() == expectedSize) {
                return true;
            }
        }
        return false;
    }
}
