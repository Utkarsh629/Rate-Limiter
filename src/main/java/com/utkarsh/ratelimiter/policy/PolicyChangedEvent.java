package com.utkarsh.ratelimiter.policy;

/** Published inside the admin transaction; acted on only after commit. */
public record PolicyChangedEvent(long policyId, Operation operation) {

    public enum Operation { CREATED, UPDATED, DELETED }
}
