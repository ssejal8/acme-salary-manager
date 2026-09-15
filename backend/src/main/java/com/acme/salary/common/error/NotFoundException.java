package com.acme.salary.common.error;

/** Requested entity does not exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    /** Convenience for the common "type + id" case, e.g. {@code of("Employee", 42)}. */
    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException(entity + " " + id + " was not found");
    }
}
