package com.acme.salary.security;

import com.acme.salary.security.dto.ChangePasswordRequest;
import com.acme.salary.security.dto.LoginRequest;
import com.acme.salary.security.dto.RefreshTokenRequest;
import com.acme.salary.security.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>Login and refresh are the only two paths in the API that a caller without a token may
 * reach, which is why they are the only two business paths in {@code SecurityConfig}'s
 * public set. Changing a password sits here too but is <b>not</b> public: it is something
 * an authenticated caller does to their own account.
 *
 * <p>There is no logout: tokens are stateless, so a client ends its session by discarding
 * them (ADR-004) — a server-side logout would have to be a lie. Changing a password is the
 * one operation that genuinely invalidates outstanding tokens, by moving the user's
 * {@code tokenVersion}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login and token refresh")
public class AuthController {

    private final AuthenticationService authentication;
    private final UserAccountService accounts;

    public AuthController(AuthenticationService authentication, UserAccountService accounts) {
        this.authentication = authentication;
        this.accounts = accounts;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Exchange credentials for tokens",
            description = """
                    Returns an access token, a refresh token, and who the caller is
                    (FR-1.1).

                    A wrong password, an unknown address and a disabled account all answer
                    the same 401 with the same message. The distinction would tell an
                    unauthenticated caller which addresses have accounts.""")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authentication.login(request);
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Exchange a refresh token for a new access token",
            description = """
                    Issues a new access token (FR-1.7). The refresh token comes back
                    unchanged rather than rotated: re-issuing it on every refresh would
                    slide its 7-day expiry forward without limit.""")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authentication.refresh(request.refreshToken());
    }

    @PostMapping("/change-password")
    // Every authenticated role: this is somebody changing their own password, so the only
    // question the role can answer is whether they are signed in at all.
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Change your own password",
            description = """
                    Changes the authenticated caller's password after confirming the
                    current one (FR-1.6). There is no user id in the request: this cannot
                    be aimed at another account.

                    **Every outstanding token stops working**, including the one that made
                    this request. `tokenVersion` moves, and the filter compares it on every
                    request (ADR-004) — so a stolen token dies with the password behind it,
                    at the cost of signing in again.

                    A wrong current password is a 400 naming the field, not a 401: the
                    caller is authenticated, so this is a failed confirmation rather than a
                    failed authentication, and a 401 would make a client end the session
                    over a typo.""")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        accounts.changeOwnPassword(request);
    }
}
