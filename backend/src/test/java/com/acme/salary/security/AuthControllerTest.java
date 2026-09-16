package com.acme.salary.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.security.dto.AuthenticatedUserResponse;
import com.acme.salary.security.dto.LoginRequest;
import com.acme.salary.security.dto.TokenResponse;
import com.acme.salary.security.jwt.InvalidTokenException;
import com.acme.salary.support.ApiSecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The HTTP contract of login and refresh, with the service mocked. No database.
 *
 * <p>Every test here is deliberately {@code @WithAnonymousUser}: these are the only two
 * business paths a caller without a token may reach, and a test that authenticated first
 * would not prove that.
 */
@WebMvcTest(controllers = AuthController.class)
@Import(ApiSecurityTestConfig.class)
class AuthControllerTest {

    private static final String VALID_BODY = """
            {"email": "hr@acme.test", "password": "Hr@12345"}
            """;

    @Autowired
    private MockMvc mockMvc;

    /** Replaces the mock {@link ApiSecurityTestConfig} supplies, so it can be stubbed. */
    @MockitoBean
    private AuthenticationService authentication;

    @Captor
    private ArgumentCaptor<LoginRequest> loginCaptor;

    private static TokenResponse tokenResponse() {
        return TokenResponse.of(
                "access-token-value",
                "refresh-token-value",
                3600,
                new AuthenticatedUserResponse(7L, "hr@acme.test", Role.HR));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @WithAnonymousUser
    void loginIsReachableWithoutATokenAndReturnsThePublishedShape() throws Exception {
        when(authentication.login(any())).thenReturn(tokenResponse());

        postJson("/api/v1/auth/login", VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token-value"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token-value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.id").value(7))
                .andExpect(jsonPath("$.user.email").value("hr@acme.test"))
                .andExpect(jsonPath("$.user.role").value("HR"));
    }

    @Test
    @WithAnonymousUser
    void theLoginResponseCarriesNothingResemblingACredential() throws Exception {
        // FR-1.2: no password field, ever, in either direction.
        when(authentication.login(any())).thenReturn(tokenResponse());

        String body = postJson("/api/v1/auth/login", VALID_BODY)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("hash")
                .doesNotContainIgnoringCase("tokenVersion");
    }

    @Test
    @WithAnonymousUser
    void theCredentialsAreBoundOntoTheRequestRecord() throws Exception {
        when(authentication.login(any())).thenReturn(tokenResponse());

        postJson("/api/v1/auth/login", VALID_BODY).andExpect(status().isOk());

        verify(authentication).login(loginCaptor.capture());
        assertThat(loginCaptor.getValue().email()).isEqualTo("hr@acme.test");
        assertThat(loginCaptor.getValue().password()).isEqualTo("Hr@12345");
    }

    @Test
    @WithAnonymousUser
    void wrongCredentialsBecome401WithAMessageThatRevealsNothing() throws Exception {
        when(authentication.login(any())).thenThrow(new BadCredentialsException("invalid email or password"));

        postJson("/api/v1/auth/login", VALID_BODY)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/login"));
    }

    @Test
    @WithAnonymousUser
    void anAddressThatIsNotAnEmailIsAFieldErrorNotAFailedLogin() throws Exception {
        postJson("/api/v1/auth/login", """
                {"email": "not-an-email", "password": "Hr@12345"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));

        verify(authentication, never()).login(any());
    }

    @Test
    @WithAnonymousUser
    void aBlankPasswordIsRejectedBeforeTheServiceIsCalled() throws Exception {
        postJson("/api/v1/auth/login", """
                {"email": "hr@acme.test", "password": ""}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));

        verify(authentication, never()).login(any());
    }

    @Test
    @WithAnonymousUser
    void anUnreadableBodyIsABadRequestNotAServerError() throws Exception {
        postJson("/api/v1/auth/login", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @WithAnonymousUser
    void refreshIsReachableWithoutATokenAndReturnsAFreshPair() throws Exception {
        when(authentication.refresh("refresh-token-value")).thenReturn(tokenResponse());

        postJson("/api/v1/auth/refresh", """
                {"refreshToken": "refresh-token-value"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token-value"))
                .andExpect(jsonPath("$.user.role").value("HR"));
    }

    @Test
    @WithAnonymousUser
    void anExpiredOrForgedRefreshTokenBecomes401() throws Exception {
        when(authentication.refresh(any())).thenThrow(new InvalidTokenException("token expired"));

        postJson("/api/v1/auth/refresh", """
                {"refreshToken": "stale"}
                """)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @WithAnonymousUser
    void theResponseToAnExpiredRefreshTokenDoesNotSayWhyItFailed() throws Exception {
        // "expired" versus "bad signature" tells an attacker which guess was closer, and
        // tells a legitimate client nothing it can act on: either way, log in again.
        when(authentication.refresh(any()))
                .thenThrow(new InvalidTokenException("token expired at Wed Sep 16 10:00:00 UTC 2026"));

        String body = postJson("/api/v1/auth/refresh", """
                {"refreshToken": "stale"}
                """)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("expired")
                .doesNotContainIgnoringCase("signature");
    }

    @Test
    @WithAnonymousUser
    void anAbsentRefreshTokenIsAFieldError() throws Exception {
        postJson("/api/v1/auth/refresh", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("refreshToken"));

        verify(authentication, never()).refresh(any());
    }
}
