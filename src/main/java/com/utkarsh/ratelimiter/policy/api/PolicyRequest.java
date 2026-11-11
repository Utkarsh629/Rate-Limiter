package com.utkarsh.ratelimiter.policy.api;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.KeyBy;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/policies} and {@code PUT /api/v1/policies/{id}}. Ranges mirror the
 * database CHECK constraints (Flyway V1) and {@link com.utkarsh.ratelimiter.algorithm.RateLimitRule}.
 */
public record PolicyRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9][a-z0-9-]*", message = "lowercase letters, digits and '-'")
        String name,

        @Size(max = 500)
        String description,

        @Schema(description = "null = any method")
        @Pattern(regexp = "GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS")
        String httpMethod,

        @Schema(example = "/api/v1/**", description = "Spring PathPattern")
        @NotBlank @Size(max = 255) @Pattern(regexp = "/.*", message = "must start with '/'")
        String endpointPattern,

        @Schema(description = "null = any tier")
        Tier tier,

        @NotNull KeyBy keyBy,

        @NotNull AlgorithmType algorithm,

        @Schema(description = "Bucket capacity, or max requests per window")
        @NotNull @Min(1) @Max(1_000_000)
        Integer limit,

        @Schema(description = "Window length, or token-bucket refill period, in ms")
        @NotNull @Min(100) @Max(86_400_000)
        Long windowMs,

        @Schema(description = "TOKEN_BUCKET only: tokens added per windowMs (default = limit)")
        @Min(1) @Max(1_000_000)
        Integer refillTokens,

        @Schema(description = "null = global default")
        FailureMode failureMode,

        @Min(-1000) @Max(1000)
        Integer priority,

        Boolean enabled,

        @Schema(description = "Required on PUT: the version you read (optimistic locking)")
        Integer version) {

    @AssertTrue(message = "refillTokens applies only to TOKEN_BUCKET")
    @Schema(hidden = true)
    public boolean isRefillTokensConsistent() {
        return refillTokens == null || algorithm == null || algorithm == AlgorithmType.TOKEN_BUCKET;
    }
}
