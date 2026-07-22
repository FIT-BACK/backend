package com.fitback.backend.global.security.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

public class CustomAccessDenied implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        ErrorCode code = ErrorCode.FORBIDDEN;

        // 응답 Content-Type, HTTP 상태코드 정의
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code.getHttpStatus().value());

        // Response Body에 응답통일한 객체를 넣기
        ApiResponse<Void> errorResponse = ApiResponse.onFailure(code.getCode(),code.getMessage(), null);

        // 실제 Response로 덮어쓰기
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}