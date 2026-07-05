package com.fitback.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleBusinessExceptionReturnsErrorCodeResponse() {
        BusinessException exception = new BusinessException(ErrorCode.NOT_FOUND);

        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleBusinessException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.NOT_FOUND.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("COMMON_404");
        assertThat(response.getBody().message()).isEqualTo("요청한 리소스를 찾을 수 없습니다.");
        assertThat(response.getBody().result()).isNull();
    }
}
