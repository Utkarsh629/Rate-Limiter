package com.utkarsh.ratelimiter.policy;

/** A policy violates a validation rule or a database CHECK constraint. Maps to HTTP 400. */
public class InvalidPolicyException extends RuntimeException {

    private final String field;

    public InvalidPolicyException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
