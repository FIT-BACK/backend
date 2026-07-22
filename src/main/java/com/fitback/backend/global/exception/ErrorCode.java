package com.fitback.backend.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400_1", "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON401_1", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON403_1", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404_1", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON405_1", "허용되지 않은 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500_1", "서버 내부 오류가 발생했습니다."),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "COMMON400_2", "요청 값이 올바르지 않습니다."),

    INVALID_ANALYSIS_IMAGE(HttpStatus.BAD_REQUEST, "ANALYSIS400_1", "JPEG, PNG, WEBP 형식의 5MB 이하 이미지만 업로드할 수 있습니다."),
    ANALYSIS_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS404_1", "분석 리포트를 찾을 수 없습니다."),
    ANALYSIS_IMAGE_STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "ANALYSIS500_1", "분석 이미지를 저장할 수 없습니다."),
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "TAG404_1", "태그를 찾을 수 없습니다."),

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH409_1", "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH401_1", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_2", "유효하지 않은 리프레시 토큰입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
