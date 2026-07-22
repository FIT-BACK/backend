package com.fitback.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleBusinessExceptionReturnsErrorCodeResponse() {
        BusinessException exception = new BusinessException(ErrorCode.NOT_FOUND);

        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleBusinessException(exception);

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
}
