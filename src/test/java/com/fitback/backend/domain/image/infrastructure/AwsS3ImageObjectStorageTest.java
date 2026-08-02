package com.fitback.backend.domain.image.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fitback.backend.domain.image.service.ImageObjectStorage.StoredImageObject;
import com.fitback.backend.global.config.ImageStorageProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.net.SocketTimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.junit.jupiter.api.Test;

class AwsS3ImageObjectStorageTest {

    @Test
    void inspectsStoredObjectMetadataAndSignature() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(1024L)
                        .contentType("image/jpeg")
                        .build()
        );
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(),
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
                )
        );
        AwsS3ImageObjectStorage storage = new AwsS3ImageObjectStorage(
                s3Client,
                new ImageStorageProperties(
                        "ap-northeast-2",
                        "fitback-test-images",
                        "https://cdn.example.com",
                        "TESTKEY",
                        "dGVzdC1wcml2YXRlLWtleQ=="
                )
        );

        StoredImageObject result = storage.inspect(
                "prod/images/analysis_original/2026/07/image.jpg"
        );

        assertThat(result.fileSizeBytes()).isEqualTo(1024L);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.signatureBytes()).containsExactly(
                (byte) 0xFF,
                (byte) 0xD8,
                (byte) 0xFF
        );
    }

    @Test
    void mapsMissingS3ObjectToImageObjectNotFound() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(404)
                        .requestId("request-404")
                        .build()
        );
        AwsS3ImageObjectStorage storage = storage(s3Client);

        assertThatThrownBy(() -> storage.inspect("object-key"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IMAGE_OBJECT_NOT_FOUND)
                );
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void mapsTemporaryS3FailureToStorageUnavailable(int statusCode) {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(statusCode)
                        .requestId("request-temporary-failure")
                        .build()
        );
        AwsS3ImageObjectStorage storage = storage(s3Client);

        assertThatThrownBy(() -> storage.inspect("object-key"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IMAGE_STORAGE_UNAVAILABLE)
                );
    }

    @ParameterizedTest
    @CsvSource({
            "400, RequestTimeout",
            "409, OperationAborted"
    })
    void mapsTemporaryS3ErrorCodeToStorageUnavailable(int statusCode, String awsErrorCode) {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(statusCode)
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode(awsErrorCode)
                                .build())
                        .requestId("request-temporary-error-code")
                        .build()
        );
        AwsS3ImageObjectStorage storage = storage(s3Client);

        assertThatThrownBy(() -> storage.inspect("object-key"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IMAGE_STORAGE_UNAVAILABLE)
                );
    }

    @Test
    void mapsS3PermissionFailureToStorageConfigurationError() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder()
                        .statusCode(403)
                        .requestId("request-403")
                        .build()
        );
        AwsS3ImageObjectStorage storage = storage(s3Client);

        assertThatThrownBy(() -> storage.inspect("object-key"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IMAGE_STORAGE_ERROR)
                );
    }

    @Test
    void mapsS3NetworkTimeoutToStorageUnavailable() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                SdkClientException.builder()
                        .message("request failed")
                        .cause(new SocketTimeoutException("timed out"))
                        .build()
        );
        AwsS3ImageObjectStorage storage = storage(s3Client);

        assertThatThrownBy(() -> storage.inspect("object-key"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IMAGE_STORAGE_UNAVAILABLE)
                );
    }

    @Test
    void logsSafeClientFailureClassificationWithoutObjectKeyOrExceptionMessage() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                SdkClientException.builder()
                        .message("sensitive endpoint details")
                        .cause(new SocketTimeoutException("sensitive timeout details"))
                        .build()
        );
        AwsS3ImageObjectStorage storage = storage(s3Client);
        Logger logger = (Logger) LoggerFactory.getLogger(AwsS3ImageObjectStorage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> storage.inspect("private/object-key"))
                    .isInstanceOf(BusinessException.class);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "operation=HEAD_OBJECT",
                                    "errorCode=IMAGE503_1",
                                    "networkOrTimeout=true",
                                    "failureTypes=SdkClientException->SocketTimeoutException"
                            )
                            .doesNotContain(
                                    "private/object-key",
                                    "sensitive endpoint details",
                                    "sensitive timeout details"
                            ));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private AwsS3ImageObjectStorage storage(S3Client s3Client) {
        return new AwsS3ImageObjectStorage(
                s3Client,
                new ImageStorageProperties(
                        "ap-northeast-2",
                        "fitback-test-images",
                        "https://cdn.example.com",
                        "TESTKEY",
                        "dGVzdC1wcml2YXRlLWtleQ=="
                )
        );
    }
}
