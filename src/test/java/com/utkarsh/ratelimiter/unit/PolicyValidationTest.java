package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.policy.api.PolicyRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PolicyValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static PolicyRequest request(String name, String method, String pattern, AlgorithmType algorithm,
                                         Integer limit, Long windowMs, Integer refill) {
        return new PolicyRequest(name, null, method, pattern, null, KeyBy.USER, algorithm, limit, windowMs, refill,
                null, null, null, null);
    }

    private Set<String> invalidFields(PolicyRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void validRequestPasses() {
        assertThat(invalidFields(request("free-api", "GET", "/api/**", AlgorithmType.TOKEN_BUCKET, 20, 60_000L, 5))).isEmpty();
    }

    @Test
    void rangesMirrorDatabaseConstraints() {
        assertThat(invalidFields(request("x", null, "/a", AlgorithmType.FIXED_WINDOW, 0, 60_000L, null))).containsExactly("limit");
        assertThat(invalidFields(request("x", null, "/a", AlgorithmType.FIXED_WINDOW, 1_000_001, 60_000L, null))).containsExactly("limit");
        assertThat(invalidFields(request("x", null, "/a", AlgorithmType.FIXED_WINDOW, 1, 99L, null))).containsExactly("windowMs");
        assertThat(invalidFields(request("x", null, "/a", AlgorithmType.FIXED_WINDOW, 1, 86_400_001L, null))).containsExactly("windowMs");
    }

    @Test
    void rejectsMalformedFields() {
        assertThat(invalidFields(request("Bad Name", null, "/a", AlgorithmType.FIXED_WINDOW, 1, 1_000L, null))).containsExactly("name");
        assertThat(invalidFields(request("x", "FETCH", "/a", AlgorithmType.FIXED_WINDOW, 1, 1_000L, null))).containsExactly("httpMethod");
        assertThat(invalidFields(request("x", null, "api/**", AlgorithmType.FIXED_WINDOW, 1, 1_000L, null))).containsExactly("endpointPattern");
        assertThat(invalidFields(request("x", null, "/a", null, null, null, null))).containsExactlyInAnyOrder("algorithm", "limit", "windowMs");
    }

    @Test
    void refillTokensOnlyForTokenBucket() {
        assertThat(invalidFields(request("x", null, "/a", AlgorithmType.SLIDING_WINDOW, 5, 1_000L, 5)))
                .containsExactly("refillTokensConsistent");
    }
}
