package com.uav.lowaltitude.platform.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean ok;
    private T data;
    private ApiError error;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = true;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.ok = false;
        r.error = new ApiError(code, message);
        return r;
    }

    public boolean isOk() {
        return ok;
    }

    public T getData() {
        return data;
    }

    public ApiError getError() {
        return error;
    }

    public static class ApiError {
        private final String code;
        private final String message;

        public ApiError(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
