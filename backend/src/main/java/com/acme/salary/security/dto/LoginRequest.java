package com.acme.salary.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials offered at login (FR-1.1).
 *
 * <p>Constraints here are shape only, and deliberately loose on the password: a minimum
 * length would tell a caller that a short guess was not even checked. Whether these
 * credentials are correct is not a validation question, and its answer is always the same
 * generic 401.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 200) String password) {
}
