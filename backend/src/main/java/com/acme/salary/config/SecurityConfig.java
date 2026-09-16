package com.acme.salary.config;

import com.acme.salary.security.AuthenticationService;
import com.acme.salary.security.JwtAuthenticationFilter;
import com.acme.salary.security.RestAccessDeniedHandler;
import com.acme.salary.security.RestAuthenticationEntryPoint;
import com.acme.salary.security.jwt.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Deny-by-default filter chain.
 *
 * <p>The API is stateless and takes a bearer token (ADR-004), so there is no session and
 * no CSRF surface. Every path outside the public set below answers 401 without a valid
 * token — nothing is reachable by accident.
 *
 * <p>Method-level {@code @PreAuthorize} is enabled here; record-level ownership checks
 * remain the service layer's job (architecture §8.1).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** Paths that must work before a caller has a token. */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    /** The SPA shell, served from the same artifact (ADR-018). */
    private static final String[] STATIC_PATHS = {
            "/", "/index.html", "/favicon.ico", "/assets/**", "/*.js", "/*.css"
    };

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthenticationService authentication,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(STATIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // Before the username/password filter, which is where a chain expects its
                // credential-reading filter to sit. It must run before authorisation is
                // evaluated, or every request would reach the entry point unauthenticated.
                //
                // Constructed here rather than injected as a bean: Boot auto-registers
                // every Filter bean into the servlet container's chain as well, which
                // would put a second copy of this filter outside the security chain at an
                // unrelated order. See JwtAuthenticationFilter.
                .addFilterBefore(
                        new JwtAuthenticationFilter(authentication),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** BCrypt, per FR-1.2. Strength left at the Spring default of 10. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
