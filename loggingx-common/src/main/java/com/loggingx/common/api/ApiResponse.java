package com.loggingx.common.api;

/**
 * Standard envelope for every HTTP response, matching the frontend's {@code { code, msg, data }} contract.
 *
 * @param code business status code; {@link #SUCCESS_CODE} on success, otherwise the HTTP status
 * @param msg  human-readable result message
 * @param data response payload, {@code null} on failure
 */
public record ApiResponse<T>(int code, String msg, T data) {

    public static final int SUCCESS_CODE = 200;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "OK", data);
    }

    public static <T> ApiResponse<T> failure(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
