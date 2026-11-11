package com.utkarsh.ratelimiter.policy;

/** What a policy's quota is counted against. */
public enum KeyBy {

    /** Per client IP address. */
    IP,

    /** Per authenticated user id; anonymous callers fall back to their IP address. */
    USER
}
