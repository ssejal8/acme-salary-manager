package com.acme.salary.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Maps every exception to the {@link ApiError} envelope in one place, so business code can
 * signal a failure without knowing anything about HTTP (architecture §4.3). Controllers
 * therefore contain no try/catch.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onBeanValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "Validation failed", request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> onConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(violation -> new ApiError.FieldError(lastNode(violation), violation.getMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "Validation failed", request, fields);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> onBusinessRule(ValidationException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ex.fieldErrors());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> onMalformedRequest(Exception ex, HttpServletRequest request) {
        // The exception text can echo payload internals, so it is logged and not returned.
        log.debug("Malformed request to {}", request.getRequestURI(), ex);
        return respond(HttpStatus.BAD_REQUEST, "Request body or parameter could not be read", request, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> onUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "Authentication is required", request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> onAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "You are not permitted to perform this action", request, List.of());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> onNotFound(NotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> onNoHandler(NoHandlerFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "No endpoint matches this path", request, List.of());
    }

    @ExceptionHandler({ConflictException.class, IllegalStateTransitionException.class})
    public ResponseEntity<ApiError> onConflict(RuntimeException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    /**
     * A constraint the service layer did not pre-check still reached the database — the
     * uniqueness guards in architecture §7.2 doing their job under a race. The cause is
     * logged; the client gets no SQL.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> onDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Database constraint rejected a request to {}", request.getRequestURI(), ex);
        return respond(HttpStatus.CONFLICT, "The request conflicts with existing data", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiError> respond(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<ApiError.FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status, message, request.getRequestURI(), fieldErrors));
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }
}
