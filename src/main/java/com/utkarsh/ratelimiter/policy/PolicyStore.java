package com.utkarsh.ratelimiter.policy;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Writes policies and turns MySQL constraint failures into domain exceptions. The API validates
 * first, so these paths are defence in depth: they fire only if validation and schema disagree or
 * two admins race on the same name.
 */
@Component
public class PolicyStore {

    private final PolicyRepository repository;

    public PolicyStore(PolicyRepository repository) {
        this.repository = repository;
    }

    /** Saves and flushes inside the caller's transaction so constraint errors surface here. */
    public RateLimitPolicy save(RateLimitPolicy policy) {
        try {
            return repository.saveAndFlush(policy);
        } catch (DataAccessException e) {
            throw translate(e, policy);
        }
    }

    public static RuntimeException translate(DataAccessException e, RateLimitPolicy policy) {
        if (MySqlErrors.findSqlException(e, MySqlErrors.DUPLICATE_ENTRY).isPresent()) {
            return new PolicyConflictException("A policy named '" + policy.getName() + "' already exists");
        }
        return MySqlErrors.checkConstraintName(e)
                .<RuntimeException>map(name -> new InvalidPolicyException(constraintField(name),
                        "Rejected by database constraint " + name))
                .orElse(e);
    }

    private static String constraintField(String constraint) {
        return switch (constraint) {
            case "chk_policy_limit" -> "limit";
            case "chk_policy_window" -> "windowMs";
            case "chk_policy_refill" -> "refillTokens";
            case "chk_policy_http_method" -> "httpMethod";
            case "chk_policy_pattern" -> "endpointPattern";
            case "chk_policy_tier" -> "tier";
            case "chk_policy_key_by" -> "keyBy";
            case "chk_policy_algorithm" -> "algorithm";
            case "chk_policy_failure_mode" -> "failureMode";
            default -> "policy";
        };
    }
}
