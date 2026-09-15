package com.acme.salary.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * The exception-to-status mapping is a published contract (architecture §4.3), so it is
 * tested directly rather than through a controller.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/employees/42");

    @Test
    void notFoundMapsTo404AndKeepsItsMessage() {
        ResponseEntity<ApiError> response =
                handler.onNotFound(NotFoundException.of("Employee", 42), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Employee 42 was not found");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/employees/42");
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }

    @Test
    void conflictMapsTo409() {
        ResponseEntity<ApiError> response =
                handler.onConflict(new ConflictException("Employee code E-001 already exists"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).contains("E-001");
    }

    @Test
    void illegalStateTransitionMapsTo409() {
        ResponseEntity<ApiError> response = handler.onConflict(
                IllegalStateTransitionException.of("Payroll run 7", "FINALISED", "DRAFT"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void businessRuleViolationMapsTo400WithFieldErrors() {
        ResponseEntity<ApiError> response = handler.onBusinessRule(
                ValidationException.field("components", "a structure must include a BASIC component"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().fieldErrors())
                .singleElement()
                .satisfies(fieldError -> {
                    assertThat(fieldError.field()).isEqualTo("components");
                    assertThat(fieldError.message()).contains("BASIC");
                });
    }

    @Test
    void accessDeniedMapsTo403WithoutRevealingWhetherTheRecordExists() {
        ResponseEntity<ApiError> response =
                handler.onAccessDenied(new AccessDeniedException("employee 42 belongs to someone else"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("You are not permitted to perform this action");
        assertThat(response.getBody().message()).doesNotContain("42");
    }

    @Test
    void unexpectedFailuresLeakNothingToTheClient() {
        ResponseEntity<ApiError> response = handler.onUnexpected(
                new IllegalStateException("could not extract ResultSet from org.hibernate.internal..."),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // NFR-2.6: no stack traces, SQL or internal class names in a response.
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("hibernate");
    }
}
