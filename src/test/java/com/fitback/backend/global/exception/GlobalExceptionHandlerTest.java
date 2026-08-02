package com.fitback.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fitback.backend.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleBusinessExceptionReturnsErrorCodeResponse() {
        BusinessException exception = new BusinessException(ErrorCode.NOT_FOUND);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");

        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleBusinessException(
                exception,
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.NOT_FOUND.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("COMMON404_1");
        assertThat(response.getBody().message()).isEqualTo("요청한 리소스를 찾을 수 없습니다.");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void handleHttpRequestMethodNotSupportedExceptionReturnsMethodNotAllowedResponse() {
        HttpRequestMethodNotSupportedException exception = new HttpRequestMethodNotSupportedException("PATCH");

        ResponseEntity<ApiResponse<Void>> response =
                globalExceptionHandler.handleHttpRequestMethodNotSupportedException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("COMMON405_1");
        assertThat(response.getBody().message()).isEqualTo("허용되지 않은 HTTP 메서드입니다.");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void handleHttpMessageNotReadableExceptionReturnsBadRequestResponse() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "invalid enum value",
                new MockHttpInputMessage(new byte[0])
        );

        ResponseEntity<ApiResponse<Void>> response =
                globalExceptionHandler.handleHttpMessageNotReadableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.BAD_REQUEST.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON400_1");
    }

    @Test
    void handleMaxUploadSizeExceededExceptionReturnsInvalidImageResponse() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(5L * 1024 * 1024);

        ResponseEntity<ApiResponse<Void>> response =
                globalExceptionHandler.handleMaxUploadSizeExceededException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.INVALID_ANALYSIS_IMAGE.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ANALYSIS400_1");
    }

    @Test
    void handleConstraintViolationExceptionReturnsPayloadFreeFailure() {
        ConstraintViolationException exception = new ConstraintViolationException(Set.of());

        ResponseEntity<ApiResponse<Void>> response =
                globalExceptionHandler.handleConstraintViolationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON400_2");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void handleServerBusinessExceptionDoesNotExposeCustomMessage() {
        BusinessException exception = new BusinessException(
                ErrorCode.IMAGE_STORAGE_ERROR,
                "https://signed.example.com/private?token=secret"
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/images/image-1/complete"
        );

        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleBusinessException(
                exception,
                request
        );

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.IMAGE_STORAGE_ERROR.getMessage());
        assertThat(response.getBody().message()).doesNotContain("signed.example.com", "secret");
    }

    @Test
    void handleServerBusinessExceptionLogsSafeRequestContext() {
        BusinessException exception = new BusinessException(
                ErrorCode.IMAGE_STORAGE_ERROR,
                "https://signed.example.com/private?X-Amz-Signature=secret-signature"
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/images/image-1/complete"
        );
        request.setQueryString("accessToken=secret-token");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            globalExceptionHandler.handleBusinessException(exception, request);

            ILoggingEvent event = appender.list.getLast();
            assertThat(event.getFormattedMessage())
                    .contains(
                            "method=POST",
                            "path=/api/v1/images/image-1/complete",
                            "errorCode=IMAGE500_2"
                    )
                    .doesNotContain(
                            "accessToken",
                            "secret-token",
                            "signed.example.com",
                            "secret-signature",
                            "X-Amz-Signature"
                    );
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage())
                    .doesNotContain(
                            "accessToken",
                            "secret-token",
                            "signed.example.com",
                            "secret-signature",
                            "X-Amz-Signature"
                    );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void handleHttpMediaTypeNotSupportedExceptionReturnsUnsupportedMediaType() {
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN,
                java.util.List.of(MediaType.APPLICATION_JSON)
        );

        ResponseEntity<ApiResponse<Void>> response =
                globalExceptionHandler.handleHttpMediaTypeNotSupportedException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON415_1");
    }

    @Test
    void handleHttpMediaTypeNotAcceptableExceptionReturnsNotAcceptable() {
        HttpMediaTypeNotAcceptableException exception =
                new HttpMediaTypeNotAcceptableException(java.util.List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ApiResponse<Void>> response =
                globalExceptionHandler.handleHttpMediaTypeNotAcceptableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.NOT_ACCEPTABLE.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON406_1");
    }

    @Test
    void handleUnexpectedExceptionPreservesCauseAndSuppressedExceptionsInLog() {
        IllegalStateException cause = new IllegalStateException("root cause");
        RuntimeException exception = new RuntimeException("unexpected failure", cause);
        exception.addSuppressed(new IllegalArgumentException("suppressed failure"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleException(
                    exception,
                    request
            );

            assertThat(response.getStatusCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus());
            ILoggingEvent event = appender.list.getLast();
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(RuntimeException.class.getName());
            assertThat(event.getThrowableProxy().getCause()).isNotNull();
            assertThat(event.getThrowableProxy().getCause().getClassName())
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getThrowableProxy().getSuppressed())
                    .extracting(suppressed -> suppressed.getClassName())
                    .containsExactly(IllegalArgumentException.class.getName());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
