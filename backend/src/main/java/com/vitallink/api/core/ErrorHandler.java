package com.vitallink.api.core;

import com.vitallink.api.core.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

/**
 * One error shape for every failure. Validation errors list field locations and messages only; they
 * never echo the submitted values (health data).
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    private static final String INVALID = "The request body or query is invalid.";

    public static ResponseEntity<Map<String, Object>> envelope(
            HttpServletRequest request, int status, String code, String message, Object detail) {
        String requestId = RequestIdFilter.current(request);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("detail", detail);
        error.put("request_id", requestId);
        return ResponseEntity.status(status)
                .header(RequestIdFilter.HEADER, requestId == null ? "" : requestId)
                .body(Map.of("error", error));
    }

    private static Map<String, Object> field(List<Object> loc, String msg) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("loc", loc);
        f.put("msg", msg);
        return f;
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, Object>> api(HttpServletRequest request, ApiError e) {
        var res = envelope(request, e.status(), e.code(), e.getMessage(), e.detail());
        if (e instanceof com.vitallink.api.core.web.RateLimiter.Limited l) {
            return ResponseEntity.status(res.getStatusCode())
                    .headers(res.getHeaders())
                    .header("Retry-After", Long.toString(l.retryAfterSeconds()))
                    .body(res.getBody());
        }
        return res;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(HttpServletRequest request, MethodArgumentNotValidException e) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) {
            List<Object> loc = new ArrayList<>(List.of("body"));
            for (String part : Naming.snake(f.getField()).split("\\.")) {
                loc.add(part);
            }
            fields.add(field(loc, f.getDefaultMessage()));
        }
        e.getBindingResult().getGlobalErrors().forEach(g -> fields.add(field(List.of("body"), g.getDefaultMessage())));
        return envelope(request, 422, "invalid_request", INVALID, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpServletRequest request, HttpMessageNotReadableException e) {
        List<Object> loc = new ArrayList<>(List.of("body"));
        String msg = "malformed JSON or wrong type";
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiError api) {
                // Thrown from a record constructor while binding (a cross-field rule).
                return envelope(request, api.status(), api.code(), api.getMessage(), api.detail());
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof JacksonException je) {
                for (JacksonException.Reference ref : je.getPath()) {
                    loc.add(ref.getPropertyName() != null ? ref.getPropertyName() : ref.getIndex());
                }
                String name = je.getClass().getSimpleName();
                if (name.contains("UnrecognizedProperty")) {
                    msg = "extra fields are not permitted";
                } else if (name.contains("InvalidFormat") || name.contains("MismatchedInput")) {
                    msg = "invalid value";
                }
                break;
            }
        }
        return envelope(request, 422, "invalid_request", INVALID, List.of(field(loc, msg)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> invalidParams(HttpServletRequest request, HandlerMethodValidationException e) {
        List<Map<String, Object>> fields = new ArrayList<>();
        e.getParameterValidationResults().forEach(r -> {
            String name = r.getMethodParameter().getParameterName();
            r.getResolvableErrors()
                    .forEach(err -> fields.add(field(List.of("query", Naming.snake(name)), err.getDefaultMessage())));
        });
        return envelope(request, 422, "invalid_request", INVALID, fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> typeMismatch(
            HttpServletRequest request, MethodArgumentTypeMismatchException e) {
        return envelope(
                request,
                422,
                "invalid_request",
                INVALID,
                List.of(field(List.of("path", e.getName()), "invalid value")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> missing(HttpServletRequest request, MissingServletRequestParameterException e) {
        return envelope(
                request,
                422,
                "invalid_request",
                INVALID,
                List.of(field(List.of("query", e.getParameterName()), "field required")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noRoute(HttpServletRequest request) {
        return envelope(request, 404, "not_found", "Not Found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpServletRequest request) {
        return envelope(request, 405, "method_not_allowed", "Method Not Allowed", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpServletRequest request) {
        return envelope(request, 415, "unsupported_media_type", "Send JSON (Content-Type: application/json).", null);
    }

    /** A query string that isn't valid UTF-8 once decoded (e.g. {@code ?days=%FF}). */
    @ExceptionHandler(org.apache.tomcat.util.http.InvalidParameterException.class)
    ResponseEntity<Map<String, Object>> badQuery(HttpServletRequest request) {
        return envelope(
                request, 422, "invalid_request", INVALID, List.of(field(List.of("query"), "malformed query string")));
    }

    /**
     * Two concurrent writes that race past a service's own check into a unique index. Services check first
     * (or lock the user row), so this is the safety net: the caller retries or reloads instead of seeing a 500.
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<Map<String, Object>> duplicate(
            HttpServletRequest request, org.springframework.dao.DuplicateKeyException e) {
        // Not the driver's message: its key detail can hold health data (a condition's label).
        log.warn("unique conflict on {} {}", request.getMethod(), request.getRequestURI());
        return envelope(
                request, 409, "conflict", "That changed at the same time somewhere else. Reload and try again.", null);
    }

    /**
     * Input the database refuses that request validation let through: a NUL character in text (Postgres
     * can't store it), a number past a column's range, a CHECK constraint. That's the caller's input, so
     * 422; anything else the database refuses is still a 500.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> refused(
            HttpServletRequest request, org.springframework.dao.DataIntegrityViolationException e) {
        String state = e.getMostSpecificCause() instanceof java.sql.SQLException sql ? sql.getSQLState() : null;
        if (state != null
                && java.util.Set.of("22021", "22001", "22003", "22007", "22008", "23514")
                        .contains(state)) {
            log.warn(
                    "database refused input on {} {} (SQLSTATE {})",
                    request.getMethod(),
                    request.getRequestURI(),
                    state);
            return envelope(request, 422, "invalid_request", INVALID, List.of(field(List.of("body"), "invalid value")));
        }
        return unexpected(request, e);
    }

    @ExceptionHandler(com.vitallink.api.core.storage.SupabaseStorage.StorageUnavailable.class)
    ResponseEntity<Map<String, Object>> storage(HttpServletRequest request, RuntimeException e) {
        log.error("file storage failed on {} {}", request.getMethod(), request.getRequestURI(), e);
        return envelope(request, 503, "storage_unavailable", "File storage isn't reachable. Try again shortly.", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(HttpServletRequest request, Exception e) {
        log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return envelope(request, 500, "internal_error", "Something went wrong on our side.", null);
    }
}
