package com.utkarsh.ratelimiter.redis;

/**
 * The circuit breaker did not permit a call to Redis (OPEN, or HALF_OPEN with no probe permit left).
 * Redis was not contacted. A subclass of {@link RateLimitStoreException}, so the filter applies the
 * policy's failure mode exactly as for a Redis error.
 */
public class RateLimitStoreUnavailableException extends RateLimitStoreException {

    public RateLimitStoreUnavailableException(String message) {
        super(message);
    }
}
