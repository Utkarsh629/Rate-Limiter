-- Rate-limit policies. Read into an in-memory snapshot on each instance; never queried per request.

CREATE TABLE rate_limit_policy (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    name             VARCHAR(100)  NOT NULL,
    description      VARCHAR(500)  NULL,
    http_method      VARCHAR(10)   NULL      COMMENT 'NULL = any method',
    endpoint_pattern VARCHAR(255)  NOT NULL  COMMENT 'Spring PathPattern, e.g. /api/v1/orders/**',
    tier             VARCHAR(20)   NULL      COMMENT 'NULL = any tier',
    key_by           VARCHAR(10)   NOT NULL  COMMENT 'IP or USER (USER falls back to IP when anonymous)',
    algorithm        VARCHAR(20)   NOT NULL,
    limit_value      INT           NOT NULL  COMMENT 'Bucket capacity or max requests per window',
    window_ms        BIGINT        NOT NULL  COMMENT 'Window length, or token-bucket refill period',
    refill_tokens    INT           NULL      COMMENT 'Token bucket only: tokens added per window_ms',
    failure_mode     VARCHAR(12)   NULL      COMMENT 'NULL = global default',
    priority         INT           NOT NULL DEFAULT 0,
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    version          INT           NOT NULL DEFAULT 0 COMMENT 'Optimistic locking',
    created_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_rate_limit_policy PRIMARY KEY (id),
    CONSTRAINT uk_rate_limit_policy_name UNIQUE (name),

    -- Defence in depth: the API validates these too, but the database must never hold a policy
    -- that would break the limiter if it were inserted by hand or by a buggy migration.
    CONSTRAINT chk_policy_http_method  CHECK (http_method IS NULL OR http_method IN ('GET','HEAD','POST','PUT','PATCH','DELETE','OPTIONS')),
    CONSTRAINT chk_policy_pattern      CHECK (endpoint_pattern LIKE '/%'),
    CONSTRAINT chk_policy_tier         CHECK (tier IS NULL OR tier IN ('ANONYMOUS','FREE','PREMIUM','ENTERPRISE')),
    CONSTRAINT chk_policy_key_by       CHECK (key_by IN ('IP','USER')),
    CONSTRAINT chk_policy_algorithm    CHECK (algorithm IN ('TOKEN_BUCKET','FIXED_WINDOW','SLIDING_WINDOW')),
    CONSTRAINT chk_policy_limit        CHECK (limit_value BETWEEN 1 AND 1000000),
    CONSTRAINT chk_policy_window       CHECK (window_ms BETWEEN 100 AND 86400000),
    CONSTRAINT chk_policy_refill       CHECK (
        (algorithm = 'TOKEN_BUCKET' AND (refill_tokens IS NULL OR refill_tokens BETWEEN 1 AND 1000000))
        OR (algorithm <> 'TOKEN_BUCKET' AND refill_tokens IS NULL)),
    CONSTRAINT chk_policy_failure_mode CHECK (failure_mode IS NULL OR failure_mode IN ('FAIL_OPEN','FAIL_CLOSED'))
) ENGINE = InnoDB;

-- Snapshot reloads read only enabled policies.
CREATE INDEX idx_rate_limit_policy_enabled ON rate_limit_policy (enabled);
