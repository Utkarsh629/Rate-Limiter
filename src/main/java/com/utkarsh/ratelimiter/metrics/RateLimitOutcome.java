package com.utkarsh.ratelimiter.metrics;

import java.util.Locale;

/** What the rate limiter decided for one request. Also the bounded {@code result} metric tag. */
public enum RateLimitOutcome {
    ALLOWED, REJECTED, FAIL_OPEN, FAIL_CLOSED;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
