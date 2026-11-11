package com.utkarsh.ratelimiter.algorithm;

import java.util.Objects;

/**
 * Identifies one quota: a policy applied to one subject. Redis key formatting lives in
 * {@link com.utkarsh.ratelimiter.redis.RateLimitKeyFactory}, not here.
 *
 * @param policyId    id of the policy whose limits apply
 * @param subjectType what the quota is counted against
 * @param subject     the user id or IP address
 */
public record RateLimitKey(long policyId, SubjectType subjectType, String subject) {

    public static final int MAX_SUBJECT_LENGTH = 1024;

    public RateLimitKey {
        if (policyId <= 0) {
            throw new IllegalArgumentException("policyId must be positive: " + policyId);
        }
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("subject must be 1.." + MAX_SUBJECT_LENGTH + " non-blank characters");
        }
    }

    public static RateLimitKey user(long policyId, String userId) {
        return new RateLimitKey(policyId, SubjectType.USER, userId);
    }

    public static RateLimitKey ip(long policyId, String ipAddress) {
        return new RateLimitKey(policyId, SubjectType.IP, ipAddress);
    }
}
