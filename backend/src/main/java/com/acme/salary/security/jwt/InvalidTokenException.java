package com.acme.salary.security.jwt;

import org.springframework.security.core.AuthenticationException;

/**
 * A presented token was absent, malformed, expired, wrongly signed, of the wrong type, or
 * issued for a user who can no longer use it.
 *
 * <p>One exception for all of those cases on purpose. Distinguishing "expired" from "bad
 * signature" in a response tells an attacker which of their guesses was closer, and
 * nothing a legitimate client can act on differently: either way it must log in again.
 * The specific reason is carried for the log, not for the response — {@code
 * RestAuthenticationEntryPoint} answers every one of them with the same 401 envelope.
 *
 * <p>Extends {@link AuthenticationException} so it also lands on the existing 401 handler
 * in {@code GlobalExceptionHandler} when thrown from a controller rather than the filter.
 */
public class InvalidTokenException extends AuthenticationException {

    public InvalidTokenException(String reason) {
        super(reason);
    }

    public InvalidTokenException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
