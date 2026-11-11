package com.utkarsh.ratelimiter.algorithm;

/**
 * Outcome of one rate-limit check. Times are milliseconds relative to the moment Redis evaluated
 * the request; HTTP-specific rounding (e.g. whole seconds for {@code Retry-After}) is applied by the
 * web layer, not here.
 *
 * @param allowed          whether the request may proceed
 * @param limit            the rule's limit (capacity / max per window)
 * @param remaining        requests that would be allowed immediately after this one
 * @param retryAfterMillis 0 when allowed; otherwise the exact wait until the same request would be allowed
 * @param resetAfterMillis wait until the full quota is available again (0 = already full)
 */
public record RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterMillis, long resetAfterMillis) {

    public RateLimitResult {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (remaining < 0 || remaining > limit) {
            throw new IllegalArgumentException("remaining out of range: " + remaining);
        }
        if (resetAfterMillis < 0) {
            throw new IllegalArgumentException("resetAfterMillis must be >= 0");
        }
        if (allowed && retryAfterMillis != 0) {
            throw new IllegalArgumentException("an allowed result has no retry delay");
        }
        if (!allowed && retryAfterMillis <= 0) {
            throw new IllegalArgumentException("a rejected result must have a positive retry delay");
        }
    }
}
