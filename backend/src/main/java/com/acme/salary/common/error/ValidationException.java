package com.acme.salary.common.error;

import java.util.List;

/**
 * A business rule rejected the input — the kind of check that needs database state and so
 * cannot be expressed as a Bean Validation annotation, such as "a salary structure must
 * include a BASIC component" or "effectiveFrom must not precede the date of joining".
 * Mapped to HTTP 400, carrying field-level messages when it can attribute them.
 */
public class ValidationException extends RuntimeException {

    private final List<ApiError.FieldError> fieldErrors;

    public ValidationException(String message) {
        this(message, List.of());
    }

    public ValidationException(String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static ValidationException field(String field, String message) {
        return new ValidationException("Validation failed", List.of(new ApiError.FieldError(field, message)));
    }

    public List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
