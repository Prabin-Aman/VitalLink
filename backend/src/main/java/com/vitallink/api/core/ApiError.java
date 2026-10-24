package com.vitallink.api.core;

/**
 * Thrown by services; {@link ErrorHandler} turns it into the error envelope (docs/05-api.md §5.1):
 *
 * <pre>{"error": {"code", "message", "detail", "request_id"}}</pre>
 *
 * Clients switch on {@code code}, never on {@code message}.
 */
public class ApiError extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Object detail;

    public ApiError(int status, String code, String message, Object detail) {
        super(message);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public ApiError(int status, String code, String message) {
        this(status, code, message, null);
    }

    public static ApiError notFound(String message) {
        return new ApiError(404, "not_found", message);
    }

    public static ApiError invalid(String message) {
        return new ApiError(422, "invalid_request", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object detail() {
        return detail;
    }
}
