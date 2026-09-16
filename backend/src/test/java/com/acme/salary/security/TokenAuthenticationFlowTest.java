package com.acme.salary.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.common.error.GlobalExceptionHandler;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.config.JacksonConfig;
import com.acme.salary.config.SecurityConfig;
import com.acme.salary.employee.EmployeeController;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import com.acme.salary.security.dto.LoginRequest;
import com.acme.salary.security.jwt.JwtProperties;
import com.acme.salary.security.jwt.JwtTokenService;
import com.acme.salary.support.ClockTestConfig;
import com.acme.salary.support.EmployeeFixtures;
import com.acme.salary.support.UserFixtures;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A real token, carried on a real request, against a real protected endpoint.
 *
 * <p>The other security tests each verify one link: {@code JwtTokenServiceTest} that a
 * token round-trips, {@code AuthenticationServiceTest} that claims resolve to a caller,
 * and the controller tests that {@code @PreAuthorize} rules hold for a caller conjured by
 * {@code @WithMockUser}. None of them proves the links are joined — that a token minted by
 * login actually satisfies the filter, populates the security context, and passes the role
 * check on an endpoint. That gap is what this class closes, and it is the gap that would
 * have made the whole feature look tested while every request still answered 401.
 *
 * <p>Everything here is genuine except the two edges: {@link UserRepository}, so no
 * database is needed, and {@link EmployeeService}, because the endpoint's own behaviour is
 * already covered elsewhere. The filter, the token service, the authentication service,
 * the password encoder and the production filter chain are all the real thing.
 */
@WebMvcTest(controllers = EmployeeController.class)
@Import({SecurityConfig.class, JacksonConfig.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class,
        AuthenticationService.class, JwtTokenService.class, ClockTestConfig.class})
class TokenAuthenticationFlowTest {

    private static final String PROTECTED_PATH = "/api/v1/employees";
    private static final String HR_EMAIL = "hr@acme.test";
    private static final String PASSWORD = "Hr@12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthenticationService authentication;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private EmployeeService employees;

    private User hrUser;

    @BeforeEach
    void setUp() {
        hrUser = UserFixtures.user(7L, HR_EMAIL, Role.HR, passwordEncoder.encode(PASSWORD));
    }

    private void userExists(User user) {
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    /** A token obtained the way a client obtains one: by logging in. */
    private String accessTokenFor(User user) {
        userExists(user);
        return authentication.login(new LoginRequest(user.getEmail(), PASSWORD)).accessToken();
    }

    private void anEmployeePageIsAvailable() {
        when(employees.search(any(), any())).thenReturn(new PageResponse<>(
                List.of(EmployeeSummaryResponse.from(
                        EmployeeFixtures.employee(1L, "E-1001", "Asha", "Menon"))),
                0, 20, 1, 1, false, false));
    }

    @Test
    void aTokenFromLoginOpensAProtectedEndpoint() throws Exception {
        String accessToken = accessTokenFor(hrUser);
        anEmployeePageIsAvailable();

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].employeeCode").value("E-1001"));
    }

    @Test
    void theSameEndpointAnswers401WithNoToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required"));

        verify(employees, never()).search(any(), any());
    }

    @Test
    void theRolesClaimIsWhatTheEndpointAuthorisesAgainst() throws Exception {
        // An EMPLOYEE token is valid and still forbidden here: 403, not 401. The
        // distinction is the whole point of FR-1.3 versus FR-1.4.
        User employeeUser = UserFixtures.user(
                9L, "asha.menon@acme.test", Role.EMPLOYEE, passwordEncoder.encode(PASSWORD));
        String accessToken = accessTokenFor(employeeUser);

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(employees, never()).search(any(), any());
    }

    @Test
    void anAdminTokenAlsoOpensTheEndpoint() throws Exception {
        User adminUser = UserFixtures.user(
                1L, "admin@acme.test", Role.ADMIN, passwordEncoder.encode(PASSWORD));
        String accessToken = accessTokenFor(adminUser);
        anEmployeePageIsAvailable();

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void aRefreshTokenPresentedAsACredentialDoesNotOpenTheEndpoint() throws Exception {
        userExists(hrUser);
        String refreshToken = authentication.login(new LoginRequest(HR_EMAIL, PASSWORD)).refreshToken();

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWhoseUserHasSinceBeenDisabledNoLongerOpensTheEndpoint() throws Exception {
        String accessToken = accessTokenFor(hrUser);

        hrUser.disable();

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        verify(employees, never()).search(any(), any());
    }

    @Test
    void aTokenIssuedBeforeAPasswordChangeNoLongerOpensTheEndpoint() throws Exception {
        String accessToken = accessTokenFor(hrUser);

        hrUser.changePassword(passwordEncoder.encode("Brand@New1"));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aForgedTokenIsRejectedRatherThanTrusted() throws Exception {
        // Correct claims, including an elevated role, but signed with the wrong key.
        String foreign = new JwtTokenService(
                new JwtProperties("an-attackers-own-signing-key-32-bytes-plus", 60, 7),
                Clock.fixed(ClockTestConfig.FIXED_INSTANT, ZoneOffset.UTC))
                .issueAccessToken(7L, HR_EMAIL, Role.ADMIN, 0);

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
        verify(employees, never()).search(any(), any());
    }

    @Test
    void aGarbageAuthorizationHeaderIs401AndNotA500() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnrecognisedAuthorizationSchemeIsIgnoredAndTheRequestStaysUnauthenticated() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic aHI6SHJAMTIzNDU="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStaleTokenDoesNotLockAUserOutOfLoggingInAgain() throws Exception {
        // The filter leaves an unusable token unauthenticated rather than rejecting the
        // request outright, so a public path still works when the client holds a stale
        // token. Otherwise a browser with an expired token in local storage could not
        // reach the one endpoint that would fix it.
        //
        // /actuator/health stands in for a public path because this slice does not load
        // AuthController, and is unmapped here — so the assertion is "anything but 401",
        // not a particular success status.
        String accessToken = accessTokenFor(hrUser);
        hrUser.disable();

        int status = mockMvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("a stale token must not make a public path answer 401")
                .isNotEqualTo(401);
    }

    @Test
    void theTokenIsReadOnEveryRequestRatherThanCachedAcrossThem() throws Exception {
        // Statelessness, stated as a test: two requests with the same token each resolve
        // the caller from scratch, so nothing is carried between them in a session.
        String accessToken = accessTokenFor(hrUser);
        anEmployeePageIsAvailable();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                    .andExpect(status().isOk());
        }

        verify(users, atLeast(2)).findByEmail(HR_EMAIL);
    }
}
