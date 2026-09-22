package com.acme.salary.common.error;

import java.util.List;

/**
 * The request conflicts with existing state: a duplicate unique value, a second payroll
 * run for a period, or a deletion blocked by a reference. Mapped to HTTP 409.
 *
 * <p>Field errors are optional and exist for the duplicate case. FR-2.2 asks for a
 * duplicate employee code or work email to come back as a 409 <em>with a field-level
 * message</em>, so the form can mark the offending control rather than showing a sentence
 * above it and leaving the user to guess which of the two is taken. A conflict that is
 * about the request as a whole — a period already has a run — carries none, which is why
 * the list is empty rather than mandatory.
 */
public class ConflictException extends RuntimeException {

    private final List<ApiError.FieldError> fieldErrors;

    public ConflictException(String message) {
        this(message, List.of());
    }

    public ConflictException(String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    /** A conflict attributable to one field, mirroring {@link ValidationException#field}. */
    public static ConflictException field(String field, String message) {
        return new ConflictException(message, List.of(new ApiError.FieldError(field, message)));
    }

    public List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
