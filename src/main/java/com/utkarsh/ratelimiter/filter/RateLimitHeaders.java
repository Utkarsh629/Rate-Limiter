package com.utkarsh.ratelimiter.filter;

/** Response header names and the millisecond-to-second conversions used for them. */
public final class RateLimitHeaders {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    /** Delta seconds until the full quota is available again (IETF draft semantics, not an epoch). */
    public static final String RESET = "X-RateLimit-Reset";
    public static final String RETRY_AFTER = "Retry-After";

    private RateLimitHeaders() {
    }

    /** {@code Retry-After}: whole seconds rounded UP (never tell a client to come back too early), minimum 1. */
    public static long retryAfterSeconds(long millis) {
        return Math.max(1, ceilSeconds(millis));
    }

    /** {@code X-RateLimit-Reset}: whole seconds rounded up; 0 only when the quota is already full. */
    public static long resetSeconds(long millis) {
        return ceilSeconds(millis);
    }

    private static long ceilSeconds(long millis) {
        return millis <= 0 ? 0 : (millis + 999) / 1000;
    }
}
