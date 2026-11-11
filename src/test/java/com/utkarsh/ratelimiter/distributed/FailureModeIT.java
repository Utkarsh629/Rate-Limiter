package com.utkarsh.ratelimiter.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.support.AppInstance;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * Redis becomes unresponsive while the application serves traffic (container paused: TCP stays open,
 * nothing answers, so every script call hits the 50 ms command timeout). Verifies the configured
 * failure modes over real HTTP, that infrastructure failures are never reported as 429, and recovery.
 *
 * <ul>
 *   <li>Instance "open": global default FAIL_OPEN.</li>
 *   <li>Instance "closed": global default FAIL_CLOSED.</li>
 *   <li>Policy demo-enterprise-endpoint (V3) overrides to FAIL_CLOSED on both.</li>
 * </ul>
 * Fewer than the circuit breaker's 10 minimum calls fail here, so every request below pays the 50 ms
 * timeout (breaker still CLOSED). Behaviour once the breaker opens is covered by CircuitBreakerIT.
 */
class FailureModeIT {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE).withExposedPorts(6379);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(InfrastructureContainers.MYSQL_IMAGE).withDatabaseName("ratelimiter");
    private static AppInstance open;
    private static AppInstance closed;

    @BeforeAll
    static void start() {
        REDIS.start();
        MYSQL.start();
        open = AppInstance.start(MYSQL, REDIS, "fail-open", "--ratelimiter.default-failure-mode=FAIL_OPEN");
        closed = AppInstance.start(MYSQL, REDIS, "fail-closed", "--ratelimiter.default-failure-mode=FAIL_CLOSED");
    }

    @AfterAll
    static void stop() {
        if (open != null) {
            open.close();
        }
        if (closed != null) {
            closed.close();
        }
        MYSQL.stop();
        REDIS.stop();
    }

    private static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static long timed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void redisOutageFollowsTheConfiguredFailureModeAndRecovers() {
        String enterprise = open.tokens().user("ent-user", Tier.ENTERPRISE);

        // Healthy: limited normally, with headers.
        assertThat(open.get("/api/v1/demo/public", null).headers().firstValue("X-RateLimit-Limit")).contains("10");
        assertThat(closed.get("/api/v1/demo/public", null).statusCode()).isEqualTo(200);
        assertThat(open.get("/api/v1/demo/enterprise", enterprise).statusCode()).isEqualTo(200);

        pauseRedis();
        try {
            // FAIL_OPEN (global default, policy has no override): request proceeds, no invented rate-limit headers.
            HttpResponse<String>[] holder = new HttpResponse[1];
            long openMs = timed(() -> holder[0] = open.get("/api/v1/demo/public", null));
            assertThat(holder[0].statusCode()).isEqualTo(200);
            assertThat(holder[0].body()).contains("\"endpoint\":\"public\"");
            assertThat(holder[0].headers().firstValue("X-RateLimit-Limit")).isEmpty();

            // FAIL_CLOSED by policy override, on the fail-open instance: 503, never 429.
            long closedMs = timed(() -> holder[0] = open.get("/api/v1/demo/enterprise", enterprise));
            assertThat(holder[0].statusCode()).isEqualTo(503);
            assertThat(holder[0].headers().firstValue("Retry-After")).contains("5");
            assertThat(holder[0].body()).contains("\"status\":503").contains("\"error\":\"Service Unavailable\"")
                    .contains("\"retryAfterSeconds\":5").doesNotContain("\"endpoint\"");

            // FAIL_CLOSED by global default.
            assertThat(closed.get("/api/v1/demo/public", null).statusCode()).isEqualTo(503);

            System.out.printf("[failure mode] Redis paused: fail-open request %d ms, fail-closed request %d ms%n", openMs, closedMs);
            assertThat(openMs).as("bounded by the command timeout, not hanging").isLessThan(2_000);
        } finally {
            unpauseRedis();
        }

        // Recovery without restart: normal limiting with headers again.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() -> {
            HttpResponse<String> response = open.get("/api/v1/demo/enterprise", enterprise);
            return response.statusCode() == 200 && response.headers().firstValue("X-RateLimit-Limit").isPresent();
        });
        assertThat(closed.get("/api/v1/demo/profile", enterprise).headers().firstValue("X-RateLimit-Limit")).contains("1000");
    }
}
