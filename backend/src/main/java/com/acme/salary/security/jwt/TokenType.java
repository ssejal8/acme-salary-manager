package com.acme.salary.security.jwt;

/**
 * What a token may be used for.
 *
 * <p>Carried as a claim and checked on every use, so the two kinds are not
 * interchangeable. Without it, a 7-day refresh token would be accepted as a bearer
 * credential on business endpoints and the 60-minute access window in FR-1.7 would be
 * decoration.
 */
public enum TokenType {

    /** Presented on business requests, short-lived. */
    ACCESS,

    /** Presented only to {@code /auth/refresh}, long-lived. */
    REFRESH
}
