package com.acme.salary.common.error;

/**
 * The request conflicts with existing state: a duplicate unique value, a second payroll
 * run for a period, or a deletion blocked by a reference. Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
