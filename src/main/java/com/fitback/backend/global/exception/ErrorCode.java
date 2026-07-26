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
    ANALYSIS_NOT_READY(HttpStatus.CONFLICT, "ANALYSIS409_1", "추천에 사용할 수 있는 분석 결과가 없습니다."),
    ANALYSIS_SELECTION_INVALID(
            HttpStatus.BAD_REQUEST,
            "ANALYSIS400_2",
            "카테고리별 선택 상품이 현재 추천 결과와 일치하지 않습니다."
    ),
    ANALYSIS_IMAGE_STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "ANALYSIS500_1", "분석 이미지를 저장할 수 없습니다."),
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "TAG404_1", "태그를 찾을 수 없습니다."),
    TREND_NOT_FOUND(HttpStatus.NOT_FOUND, "TREND404_1", "트렌드를 찾을 수 없습니다."),

    CLOSET_NOT_FOUND(HttpStatus.NOT_FOUND, "CLOSET404_1", "저장한 항목을 찾을 수 없습니다."),
    CLOSET_ALREADY_SAVED(HttpStatus.BAD_REQUEST, "CLOSET400_1", "이미 저장한 항목입니다."),
    CLOSET_TARGET_UNSUPPORTED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "CLOSET422_1",
            "이 API에서 지원하지 않는 저장 대상입니다."
    ),

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH409_1", "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH401_1", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_2", "유효하지 않은 리프레시 토큰입니다."),
    SOCIAL_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "AUTH400_1", "카카오 이메일 제공 동의가 필요합니다."),
    SOCIAL_ID_REQUIRED(HttpStatus.BAD_REQUEST, "AUTH400_2", "카카오 회원 식별값을 확인할 수 없습니다."),
    INVALID_TEMP_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_3", "유효하지 않은 임시 토큰입니다."),
    INVALID_PASSWORD_RESET_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_4", "유효하지 않거나 만료된 비밀번호 재설정 토큰입니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "MEMBER409_1", "이미 사용중인 닉네임입니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "MEMBER400_1", "현재 비밀번호가 일치하지 않습니다."),
    PASSWORD_CHANGE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "MEMBER400_2", "소셜 로그인 회원은 비밀번호를 변경할 수 없습니다."),
    MEMBER_TAG_NOT_FOUND(HttpStatus.BAD_REQUEST, "MEMBER400_3", "존재하지 않는 태그가 포함되어 있습니다."),
    REJOIN_BLOCKED(HttpStatus.FORBIDDEN, "MEMBER403_1", "탈퇴 후 30일 동안 재가입할 수 없습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER404_1", "회원을 찾을 수 없습니다."),

    NOTIFICATION_SETTING_EMPTY(HttpStatus.BAD_REQUEST, "NOTIFICATION400_1", "변경할 알림 설정 값이 없습니다."),

    IMAGE_UNSUPPORTED_CONTENT_TYPE(
            HttpStatus.BAD_REQUEST,
            "IMAGE400_1",
            "지원하지 않는 이미지 형식입니다."
    ),
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "IMAGE404_1", "이미지를 찾을 수 없습니다."),
    IMAGE_INVALID_STATE(HttpStatus.CONFLICT, "IMAGE409_1", "현재 상태에서는 이미지를 사용할 수 없습니다."),
    IMAGE_UPLOAD_EXPIRED(HttpStatus.GONE, "IMAGE410_1", "이미지 업로드 요청이 만료되었습니다."),
    INVALID_IMAGE_CONTENT(HttpStatus.UNPROCESSABLE_ENTITY, "IMAGE422_1", "실제 이미지 내용이 올바르지 않습니다."),
    IMAGE_PRESIGN_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE500_1", "이미지 업로드 정보를 발급할 수 없습니다."),
    IMAGE_STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE500_2", "이미지 저장소 처리에 실패했습니다."),

    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT404_1", "상품을 찾을 수 없습니다."),
    PRODUCT_REFERENCE_INVALID(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "PRODUCT422_1",
            "상품 후보 정보가 유효하지 않습니다."
    ),
    PRODUCT_REFERENCE_UNSUPPORTED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "PRODUCT422_2",
            "상세 조회를 지원하지 않는 상품 후보입니다."
    ),
    PRODUCT_PROVIDER_RESPONSE_INVALID(
            HttpStatus.BAD_GATEWAY,
            "PRODUCT502_1",
            "상품 공급자 응답을 처리할 수 없습니다."
    ),
    PRODUCT_PROVIDER_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PRODUCT503_1",
            "상품 공급자를 일시적으로 사용할 수 없습니다."
    ),
    PRODUCT_PROVIDER_QUOTA_EXCEEDED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PRODUCT503_2",
            "상품 공급자 요청 한도를 초과했습니다."
    ),
    PRODUCT_PROVIDER_PERSISTENCE_UNSUPPORTED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PRODUCT503_3",
            "저장 가능한 상품 후보를 찾을 수 없습니다."
    ),
    RECOMMENDATION_INPUT_CHANGED(
            HttpStatus.CONFLICT,
            "RECOMMENDATION409_1",
            "추천 생성 중 분석 결과가 변경되었습니다."
    );

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
