package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A persisted rate-limit policy (table {@code rate_limit_policy}, Flyway V1). Only the admin API and
 * the policy snapshot loader read this entity; request handling uses the compiled
 * {@link ResolvedPolicy} snapshot and never touches the database.
 */
@Entity
@Table(name = "rate_limit_policy")
public class RateLimitPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "endpoint_pattern", nullable = false)
    private String endpointPattern;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_by", nullable = false, length = 10)
    private KeyBy keyBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlgorithmType algorithm;

    @Column(name = "limit_value", nullable = false)
    private int limitValue;

    @Column(name = "window_ms", nullable = false)
    private long windowMs;

    @Column(name = "refill_tokens")
    private Integer refillTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_mode", length = 12)
    private FailureMode failureMode;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    @Version
    @Column(nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getEndpointPattern() { return endpointPattern; }
    public void setEndpointPattern(String endpointPattern) { this.endpointPattern = endpointPattern; }
    public Tier getTier() { return tier; }
    public void setTier(Tier tier) { this.tier = tier; }
    public KeyBy getKeyBy() { return keyBy; }
    public void setKeyBy(KeyBy keyBy) { this.keyBy = keyBy; }
    public AlgorithmType getAlgorithm() { return algorithm; }
    public void setAlgorithm(AlgorithmType algorithm) { this.algorithm = algorithm; }
    public int getLimitValue() { return limitValue; }
    public void setLimitValue(int limitValue) { this.limitValue = limitValue; }
    public long getWindowMs() { return windowMs; }
    public void setWindowMs(long windowMs) { this.windowMs = windowMs; }
    public Integer getRefillTokens() { return refillTokens; }
    public void setRefillTokens(Integer refillTokens) { this.refillTokens = refillTokens; }
    public FailureMode getFailureMode() { return failureMode; }
    public void setFailureMode(FailureMode failureMode) { this.failureMode = failureMode; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Compiles this row into the immutable form used on the request path. */
    public ResolvedPolicy toResolved() {
        return ResolvedPolicy.compile(id, name, httpMethod, endpointPattern, tier, keyBy, algorithm,
                limitValue, windowMs, refillTokens, failureMode, priority, version);
    }
}
