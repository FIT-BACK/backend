package com.fitback.backend.global.exception;

import com.fitback.backend.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        boolean serverError = errorCode.getHttpStatus().is5xxServerError();
        if (serverError) {
            log.error(
                    "Business exception response. method={}, path={}, errorCode={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    errorCode.getCode(),
                    stackTraceOnly(exception)
            );
        }
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.onFailure(
                        errorCode.getCode(),
                        serverError ? errorCode.getMessage() : exception.getMessage()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        return handleFailure(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        return handleFailure(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception
    ) {
        return handleFailure(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception
    ) {
        return handleFailure(ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        return handleFailure(ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception
    ) {
        return handleFailure(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestPartException(
            MissingServletRequestPartException exception
    ) {
        return handleFailure(ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception
    ) {
        return handleFailure(ErrorCode.INVALID_ANALYSIS_IMAGE);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException exception
    ) {
        return handleFailure(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException exception
    ) {
        return handleFailure(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotAcceptableException(
            HttpMediaTypeNotAcceptableException exception
    ) {
        return handleFailure(ErrorCode.NOT_ACCEPTABLE);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException exception) {
        return handleFailure(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        return handleFailure(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFoundException(NoHandlerFoundException exception) {
        return handleFailure(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled exception response. method={}, path={}, errorCode={}, failureType={}",
                request.getMethod(),
                request.getRequestURI(),
                ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                exception.getClass().getSimpleName(),
                exception
        );
        return handleFailure(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> handleFailure(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.onFailure(errorCode.getCode(), errorCode.getMessage()));
    }

    private RuntimeException stackTraceOnly(Exception exception) {
        RuntimeException sanitized = new RuntimeException(exception.getClass().getSimpleName());
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }
}
