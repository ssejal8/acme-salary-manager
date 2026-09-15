package com.acme.salary.common.error;

/**
 * An entity was asked to move between states its lifecycle does not allow — finalising an
 * already-finalised payroll run, or editing a finalised one (ADR-010). Mapped to HTTP 409.
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }

    public static IllegalStateTransitionException of(String entity, Object from, Object to) {
        return new IllegalStateTransitionException(
                entity + " cannot move from " + from + " to " + to);
    }
}
