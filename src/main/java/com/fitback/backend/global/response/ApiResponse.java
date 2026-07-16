package com.fitback.backend.global.response;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {

    private static final String SUCCESS_CODE = "COMMON200_1";
    private static final String SUCCESS_MESSAGE = "성공적으로 요청을 처리했습니다.";
    private static final String CREATED_CODE = "COMMON201_1";
    private static final String CREATED_MESSAGE = "리소스가 생성되었습니다.";

    public static <T> ApiResponse<T> onSuccess(T data) {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static ApiResponse<Void> onSuccess() {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> onCreated(T data) {
        return new ApiResponse<>(true, CREATED_CODE, CREATED_MESSAGE, data);
    }

    public static ApiResponse<Void> onCreated() {
        return new ApiResponse<>(true, CREATED_CODE, CREATED_MESSAGE, null);
    }

    public static ApiResponse<Void> onFailure(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }
}
