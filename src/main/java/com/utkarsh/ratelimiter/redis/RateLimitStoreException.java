package com.utkarsh.ratelimiter.redis;

/**
 * The rate-limit state store could not answer: Redis down, timed out, or returned something
 * unexpected. Callers decide between fail-open and fail-closed (Phase 5); algorithms never guess.
 */
public class RateLimitStoreException extends RuntimeException {

    public RateLimitStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public RateLimitStoreException(String message) {
        super(message);
    }
}
