-- Default tier policies from the requirements. All apply to every /api/** endpoint.
-- They can be changed at runtime through the policy API (Phase 3).

INSERT INTO rate_limit_policy
    (name, description, http_method, endpoint_pattern, tier, key_by, algorithm, limit_value, window_ms, refill_tokens, failure_mode, priority)
VALUES
    ('anonymous-default', 'Unauthenticated callers, limited per client IP',
        NULL, '/api/**', 'ANONYMOUS', 'IP', 'SLIDING_WINDOW', 10, 60000, NULL, NULL, 0),
    ('free-tier', 'FREE tier: 20 requests per minute per user',
        NULL, '/api/**', 'FREE', 'USER', 'TOKEN_BUCKET', 20, 60000, 20, NULL, 0),
    ('premium-tier', 'PREMIUM tier: 100 requests per minute per user',
        NULL, '/api/**', 'PREMIUM', 'USER', 'TOKEN_BUCKET', 100, 60000, 100, NULL, 0),
    ('enterprise-tier', 'ENTERPRISE tier: 1,000 requests per minute per user',
        NULL, '/api/**', 'ENTERPRISE', 'USER', 'TOKEN_BUCKET', 1000, 60000, 1000, NULL, 0);
