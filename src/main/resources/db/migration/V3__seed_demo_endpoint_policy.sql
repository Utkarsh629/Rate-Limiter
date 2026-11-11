-- Endpoint-specific demo policy: ENTERPRISE callers of GET /api/v1/demo/enterprise get a tighter,
-- sliding-window limit than their tier default (more specific path wins, see docs/07), and the
-- endpoint FAILS CLOSED (503) if Redis is unavailable, unlike the fail-open tier defaults.

INSERT INTO rate_limit_policy
    (name, description, http_method, endpoint_pattern, tier, key_by, algorithm, limit_value, window_ms, refill_tokens, failure_mode, priority)
VALUES
    ('demo-enterprise-endpoint', 'Enterprise demo endpoint: 50 per minute per user, fail-closed',
        'GET', '/api/v1/demo/enterprise', 'ENTERPRISE', 'USER', 'SLIDING_WINDOW', 50, 60000, NULL, 'FAIL_CLOSED', 0);
