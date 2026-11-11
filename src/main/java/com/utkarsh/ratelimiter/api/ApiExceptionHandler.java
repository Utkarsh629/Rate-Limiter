package com.utkarsh.ratelimiter.api;

import com.utkarsh.ratelimiter.policy.InvalidPolicyException;
import com.utkarsh.ratelimiter.policy.PolicyConflictException;
import com.utkarsh.ratelimiter.policy.PolicyNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 7807 problem responses for the REST API. Field-level validation errors are listed under
 * {@code errors}. Internal details (SQL, stack traces) are never returned.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PolicyNotFoundException.class)
    ProblemDetail notFound(PolicyNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Policy not found", e.getMessage());
    }

    @ExceptionHandler(PolicyConflictException.class)
    ProblemDetail conflict(PolicyConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(InvalidPolicyException.class)
    ProblemDetail invalid(InvalidPolicyException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid policy", e.getMessage());
        problem.setProperty("errors", List.of(Map.of("field", e.field(), "message", e.getMessage())));
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "Request parameters are invalid");
        problem.setProperty("errors", e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList());
        return problem;
    }

    /** Policy database unreachable: the admin API is unavailable, request handling is not affected. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ProblemDetail databaseUnavailable(RuntimeException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Policy store unavailable", "The policy database is unavailable; retry later");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException e,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "Request body failed validation");
        List<Map<String, String>> errors = new java.util.ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(f ->
                errors.add(Map.of("field", f.getField().replace("refillTokensConsistent", "refillTokens"),
                        "message", String.valueOf(f.getDefaultMessage()))));
        e.getBindingResult().getGlobalErrors().forEach(g ->
                errors.add(Map.of("field", g.getObjectName(), "message", String.valueOf(g.getDefaultMessage()))));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
