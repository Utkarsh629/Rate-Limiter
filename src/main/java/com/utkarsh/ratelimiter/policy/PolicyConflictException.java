package com.utkarsh.ratelimiter.policy;

/** Duplicate name or stale version (optimistic locking). Maps to HTTP 409. */
public class PolicyConflictException extends RuntimeException {

    public PolicyConflictException(String message) {
        super(message);
    }
}
