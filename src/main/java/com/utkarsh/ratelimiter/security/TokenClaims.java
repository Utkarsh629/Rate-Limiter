package com.utkarsh.ratelimiter.security;

/** Custom JWT claim names. The user id is the standard {@code sub} claim. */
public final class TokenClaims {

    public static final String TIER = "tier";
    public static final String ROLES = "roles";

    private TokenClaims() {
    }
}
