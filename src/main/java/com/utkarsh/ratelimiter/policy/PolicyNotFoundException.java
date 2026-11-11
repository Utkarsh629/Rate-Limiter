package com.utkarsh.ratelimiter.policy;

/** Maps to HTTP 404. */
public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(long id) {
        super("Policy " + id + " not found");
    }
}
