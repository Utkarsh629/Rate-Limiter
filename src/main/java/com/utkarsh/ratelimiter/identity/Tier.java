package com.utkarsh.ratelimiter.identity;

/** Subscription tier. ANONYMOUS is assigned to unauthenticated callers and never comes from a token. */
public enum Tier {
    ANONYMOUS,
    FREE,
    PREMIUM,
    ENTERPRISE
}
