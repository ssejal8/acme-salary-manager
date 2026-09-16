package com.acme.salary.security;

import com.acme.salary.security.jwt.InvalidTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the security context from a bearer token (FR-1.3).
 *
 * <p>The piece ADR-004 left for the auth feature. With it in place the deny-by-default
 * chain in {@code SecurityConfig} finally has a way to say yes, and the {@code
 * @PreAuthorize} role rules already on the controllers start applying to real callers
 * rather than only to mocked ones in tests.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Spring Security's {@code hasRole('HR')} tests for the authority {@code ROLE_HR}.
     * The prefix is convention, not magic, so it has to be added here.
     */
    private static final String ROLE_PREFIX = "ROLE_";

    private final AuthenticationService authentication;

    public JwtAuthenticationFilter(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(request);
        if (token != null) {
            try {
                authenticate(request, token);
            } catch (InvalidTokenException e) {
                // Left unauthenticated rather than answered with 401 here, for two
                // reasons. A protected path still ends in 401 from the entry point, so
                // nothing is let through; and a public path — /auth/login above all —
                // keeps working when the client happens to hold a stale token. Rejecting
                // outright would lock a user out of the one endpoint that could fix it.
                SecurityContextHolder.clearContext();
                log.debug("Ignoring token on {} {}: {}",
                        request.getMethod(), request.getRequestURI(), e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        CurrentUser user = authentication.authenticate(token);

        // The principal is the email because it is this system's username (FR-2.7), and
        // because CurrentUserProvider resolves the actor from Authentication#getName.
        UsernamePasswordAuthenticationToken authenticated = new UsernamePasswordAuthenticationToken(
                user.email(),
                null,
                List.of(new SimpleGrantedAuthority(ROLE_PREFIX + user.role().name())));
        authenticated.setDetails(request.getRequestURI());

        // A fresh context rather than mutating the existing one: the holder's strategy may
        // share the current instance across threads.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
    }

    /**
     * The token from {@code Authorization: Bearer <token>}, or null when the header is
     * absent or uses another scheme.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }
}
