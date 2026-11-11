package com.utkarsh.ratelimiter.support;

import com.utkarsh.ratelimiter.policy.PolicyCache;
import com.utkarsh.ratelimiter.security.JwtProperties;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application on a random port against Testcontainers MySQL + Redis. Shares one Spring context
 * (and one pair of containers) with every other test class using the same configuration. Policies
 * created by tests must be named {@code it-*}; they are removed after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(InfrastructureContainers.class)
public abstract class ApiTestSupport {

    @Autowired protected TestRestTemplate http;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PolicyCache policyCache;
    @Autowired private JwtProperties jwtProperties;

    protected TestTokens tokens;

    @Autowired protected org.springframework.data.redis.core.StringRedisTemplate redis;

    @BeforeEach
    void initTokens() {
        tokens = new TestTokens(jwtProperties);
    }

    /**
     * Every test starts with no rate-limit state. Tests share one context and therefore one Redis, and
     * all anonymous requests come from the same loopback IP, so quotas would otherwise leak between tests.
     */
    @BeforeEach
    void clearRateLimitState() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterEach
    void removeTestPolicies() {
        if (jdbc.update("DELETE FROM rate_limit_policy WHERE name LIKE 'it-%'") > 0) {
            policyCache.reload("test cleanup");
        }
    }

    protected <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    protected ResponseEntity<String> get(String path, String token) {
        return call(HttpMethod.GET, path, token, null, String.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected ResponseEntity<Map<String, Object>> callForMap(HttpMethod method, String path, String token, Object body) {
        return (ResponseEntity) call(method, path, token, body, Map.class);
    }
}
