package com.adrninistrator.javacg2.platform.dto;

public record ApiResponse<T>(
    boolean success,
    T data,
    ErrorResponse error
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String errorType, String message, String suggestion) {
        return new ApiResponse<>(false, null, new ErrorResponse(errorType, message, suggestion));
    }
}
