package com.acme.salary.security;

import com.acme.salary.security.dto.LoginRequest;
import com.acme.salary.security.dto.RefreshTokenRequest;
import com.acme.salary.security.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>The only two paths in the API that a caller without a token may reach, which is why
 * they are the only two business paths listed in {@code SecurityConfig}'s public set.
 * There is no logout: tokens are stateless, so a client ends its session by discarding
 * them (ADR-004) — a server-side logout would have to be a lie.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login and token refresh")
public class AuthController {

    private final AuthenticationService authentication;

    public AuthController(AuthenticationService authentication) {
        this.authentication = authentication;
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
}
