package com.acme.salary.support;

import com.acme.salary.common.error.GlobalExceptionHandler;
import com.acme.salary.config.JacksonConfig;
import com.acme.salary.config.SecurityConfig;
import com.acme.salary.security.AuthenticationService;
import com.acme.salary.security.JwtAuthenticationFilter;
import com.acme.salary.security.RestAccessDeniedHandler;
import com.acme.salary.security.RestAuthenticationEntryPoint;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The real request pipeline, for a {@code @WebMvcTest} of any controller.
 *
 * <p>Every controller test in this project imports the production {@link SecurityConfig}
 * rather than stubbing it, so that the deny-by-default chain and the {@code @PreAuthorize}
 * role rules are genuinely exercised against the endpoint under test (FR-1.3, FR-1.4).
 * That needs the same six collaborators every time, and this is them in one place.
 *
 * <p>{@link JwtAuthenticationFilter} is included because it is now part of the chain, and
 * a test of the chain that omitted it would be testing a chain that does not exist. It is
 * a pass-through for these tests: they authenticate with {@code @WithMockUser}, so no
 * request carries an {@code Authorization} header and the filter never reaches the service
 * below.
 */
@TestConfiguration
@Import({SecurityConfig.class, JacksonConfig.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
public class ApiSecurityTestConfig {

    /**
     * A mock rather than {@code @MockitoBean} on each test class: the filter needs the
     * dependency to exist, and no test that uses this config drives a token through it.
     * {@code AuthenticationService} owns a {@code PasswordEncoder} and a signing key, and
     * a real one here would mean every controller test carried both for nothing.
     */
    @Bean
    public AuthenticationService authenticationService() {
        return Mockito.mock(AuthenticationService.class);
    }
}
