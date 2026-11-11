package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 1 acceptance: the application boots against real Redis and MySQL, Flyway applies the
 * schema and seed data, the schema's constraints are enforced, readiness reports UP (R1 semantics),
 * and dependency health is visible separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(InfrastructureContainers.class)
class InfrastructureIT {

    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired TestRestTemplate http;

    @Test
    void redisAnswersPing() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @Test
    void redisRunsLuaScriptsThatReadServerTime() {
        // Phase 2 scripts depend on calling TIME inside Lua (single clock for all instances).
        var script = new DefaultRedisScript<>(
                "local t = redis.call('TIME'); return tonumber(t[1])", Long.class);

        Long redisEpochSeconds = redis.execute(script, List.of());

        assertThat(redisEpochSeconds).isNotNull();
        assertThat(Math.abs(redisEpochSeconds - Instant.now().getEpochSecond())).isLessThan(60);
    }

    @Test
    void mysqlIsReachableAndIsVersion84() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        assertThat(version).startsWith("8.4");
    }

    @Test
    void flywayAppliedAllMigrationsSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).hasSize(3);
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState))
                .containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void seedPoliciesExistForEveryTier() {
        List<String> rows = jdbc.queryForList(
                "SELECT CONCAT(tier, ':', algorithm, ':', limit_value, '/', window_ms) "
                        + "FROM rate_limit_policy WHERE id <= 4 ORDER BY limit_value", // V2 seed rows only
                String.class);

        assertThat(rows).containsExactly(
                "ANONYMOUS:SLIDING_WINDOW:10/60000",
                "FREE:TOKEN_BUCKET:20/60000",
                "PREMIUM:TOKEN_BUCKET:100/60000",
                "ENTERPRISE:TOKEN_BUCKET:1000/60000");
    }

    @Test
    void schemaRejectsNonPositiveLimit() {
        assertCheckConstraintViolated(() -> insertPolicy("bad-limit", "FIXED_WINDOW", 0, null),
                "chk_policy_limit");
    }

    @Test
    void schemaRejectsRefillTokensOnNonTokenBucketPolicy() {
        assertCheckConstraintViolated(() -> insertPolicy("bad-refill", "FIXED_WINDOW", 10, 10),
                "chk_policy_refill");
    }

    @Test
    void schemaRejectsDuplicatePolicyName() {
        assertThatThrownBy(() -> insertPolicy("free-tier", "TOKEN_BUCKET", 10, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readinessGatesOnInitialisationAndPoliciesOnly() {
        // Decision R1 (2D): Redis/MySQL availability does not gate traffic. Superseded the Phase 1 group
        // (readinessState, db, redis).
        ResponseEntity<String> response = http.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"status\":\"UP\"")
                .contains("\"readinessState\"")
                .contains("\"policies\"")
                .doesNotContain("\"db\"")
                .doesNotContain("\"redis\"");
    }

    @Test
    void dependencyHealthIsVisibleInItsOwnGroup() {
        ResponseEntity<String> response = http.getForEntity("/actuator/health/dependencies", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"db\"")
                .contains("\"redis\"")
                .contains("\"rateLimitStore\"")
                .contains("\"circuitBreaker\":\"CLOSED\"");
    }

    /**
     * MySQL reports CHECK violations as error 3819 with SQLState HY000, which Spring's exception
     * translator does not map to DataIntegrityViolationException. Assert on the vendor error code
     * so the test proves the database itself enforced the rule.
     */
    private static void assertCheckConstraintViolated(Runnable insert, String constraintName) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Check constraint '" + constraintName + "' is violated")
                .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(3819));
    }

    private void insertPolicy(String name, String algorithm, int limit, Integer refillTokens) {
        jdbc.update("""
                INSERT INTO rate_limit_policy
                    (name, endpoint_pattern, key_by, algorithm, limit_value, window_ms, refill_tokens)
                VALUES (?, '/api/**', 'IP', ?, ?, 60000, ?)
                """, name, algorithm, limit, refillTokens);
    }
}
