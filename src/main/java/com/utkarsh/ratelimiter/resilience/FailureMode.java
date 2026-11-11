package com.utkarsh.ratelimiter.resilience;

/**
 * What the limiter does when Redis cannot be consulted (down, timed out, or circuit open).
 */
public enum FailureMode {

    /** Allow the request. Favours availability; limits are not enforced during the outage. */
    FAIL_OPEN,

    /** Reject the request with HTTP 503 (not 429). Favours protection of the downstream resource. */
    FAIL_CLOSED
}
