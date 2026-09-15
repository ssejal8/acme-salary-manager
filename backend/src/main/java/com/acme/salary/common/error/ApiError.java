package com.acme.salary.common.error;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * The single error envelope every failing request returns, as published in the README.
 *
 * <p>It deliberately carries no stack trace, SQL or internal class name (NFR-2.6).
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    /** One per rejected input field, so a client can attach messages to form controls. */
    public record FieldError(String field, String message) {
    }

    public static ApiError of(HttpStatus status, String message, String path) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, List.of());
    }

    public static ApiError of(HttpStatus status, String message, String path, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
    }
}
