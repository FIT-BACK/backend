package com.fitback.backend.global.response;

public record ApiResponse<T>(
        boolean isSuccess,
        String code,
        String message,
        T result
) {

    private static final String SUCCESS_CODE = "COMMON_200";
    private static final String SUCCESS_MESSAGE = "요청에 성공했습니다.";

    public static <T> ApiResponse<T> onSuccess(T result) {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, result);
    }

    public static ApiResponse<Void> onSuccess() {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> onFailure(String code, String message, T result) {
        return new ApiResponse<>(false, code, message, result);
    }
}
