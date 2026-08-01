package com.fitback.backend.domain.image.infrastructure;

import com.fitback.backend.domain.image.service.ImageObjectStorage;
import com.fitback.backend.global.config.ImageStorageProperties;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class AwsS3ImageObjectStorage implements ImageObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(AwsS3ImageObjectStorage.class);
    private static final String SIGNATURE_BYTE_RANGE = "bytes=0-11";
    private static final String REQUEST_TIMEOUT_ERROR = "RequestTimeout";
    private static final String OPERATION_ABORTED_ERROR = "OperationAborted";

    private final S3Client s3Client;
    private final ImageStorageProperties properties;

    @Override
    public StoredImageObject inspect(String objectKey) {
        HeadObjectResponse metadata = execute("HEAD_OBJECT", () ->
                s3Client.headObject(HeadObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build())
        );
        ResponseBytes<GetObjectResponse> signature = execute("GET_OBJECT_RANGE", () ->
                s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .range(SIGNATURE_BYTE_RANGE)
                        .build())
        );
        return new StoredImageObject(
                metadata.contentLength(),
                metadata.contentType(),
                signature.asByteArray()
        );
    }

    @Override
    public void delete(String objectKey) {
        execute("DELETE_OBJECT", () ->
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build())
        );
    }

    private <T> T execute(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (S3Exception exception) {
            throw translateS3Exception(operation, exception);
        } catch (SdkClientException exception) {
            throw translateClientException(operation, exception);
        }
    }

    private BusinessException translateS3Exception(
            String operation,
            S3Exception exception
    ) {
        int statusCode = exception.statusCode();
        String awsErrorCode = exception.awsErrorDetails() == null
                ? null
                : exception.awsErrorDetails().errorCode();
        ErrorCode errorCode;
        if (statusCode == 404) {
            errorCode = ErrorCode.IMAGE_OBJECT_NOT_FOUND;
        } else if (isTemporaryS3Failure(statusCode, awsErrorCode)) {
            errorCode = ErrorCode.IMAGE_STORAGE_UNAVAILABLE;
        } else {
            errorCode = ErrorCode.IMAGE_STORAGE_ERROR;
        }
        if (errorCode == ErrorCode.IMAGE_OBJECT_NOT_FOUND) {
            log.warn(
                    "S3 object not found. operation={}, statusCode={}, awsErrorCode={}, "
                            + "awsRequestId={}, errorCode={}",
                    operation,
                    statusCode,
                    awsErrorCode,
                    exception.requestId(),
                    errorCode.getCode()
            );
        } else {
            log.error(
                    "S3 request failed. operation={}, statusCode={}, awsErrorCode={}, "
                            + "awsRequestId={}, errorCode={}",
                    operation,
                    statusCode,
                    awsErrorCode,
                    exception.requestId(),
                    errorCode.getCode()
            );
        }
        return new BusinessException(errorCode);
    }

    private boolean isTemporaryS3Failure(int statusCode, String awsErrorCode) {
        return statusCode == 408
                || statusCode == 429
                || statusCode >= 500
                || (statusCode == 400 && REQUEST_TIMEOUT_ERROR.equals(awsErrorCode))
                || (statusCode == 409 && OPERATION_ABORTED_ERROR.equals(awsErrorCode));
    }

    private BusinessException translateClientException(
            String operation,
            SdkClientException exception
    ) {
        boolean externalFailure = hasNetworkOrTimeoutCause(exception);
        ErrorCode errorCode = externalFailure
                ? ErrorCode.IMAGE_STORAGE_UNAVAILABLE
                : ErrorCode.IMAGE_STORAGE_ERROR;
        log.error(
                "S3 client request failed. operation={}, errorCode={}, "
                        + "networkOrTimeout={}, failureTypes={}",
                operation,
                errorCode.getCode(),
                externalFailure,
                failureTypes(exception)
        );
        return new BusinessException(errorCode);
    }

    private String failureTypes(Throwable exception) {
        StringBuilder types = new StringBuilder();
        Throwable current = exception;
        while (current != null) {
            if (!types.isEmpty()) {
                types.append("->");
            }
            types.append(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return types.toString();
    }

    private boolean hasNetworkOrTimeoutCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof IOException
                    || current instanceof TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
