package com.utkarsh.ratelimiter.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.PolicyCache;
import com.utkarsh.ratelimiter.support.AppInstance;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import com.utkarsh.ratelimiter.support.PrometheusText;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * /stats across three real instances (flush every 1 s): exact per-instance and total counts, the raw
 * Prometheus counters of the three instances summed, and a Redis outage in the middle of flushing.
 */
class StatsDistributedIT {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE).withExposedPorts(6379);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(InfrastructureContainers.MYSQL_IMAGE).withDatabaseName("ratelimiter");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static List<AppInstance> apps;
    private static long policyId;

    @BeforeAll
    static void start() throws Exception {
        REDIS.start();
        MYSQL.start();
        apps = List.of(
                AppInstance.start(MYSQL, REDIS, "app-1", "--ratelimiter.stats.flush-interval=1s"),
                AppInstance.start(MYSQL, REDIS, "app-2", "--ratelimiter.stats.flush-interval=1s"),
                AppInstance.start(MYSQL, REDIS, "app-3", "--ratelimiter.stats.flush-interval=1s"));
        String body = apps.get(0).send("POST", "/api/v1/policies", apps.get(0).tokens().admin(),
                "{\"name\":\"it-dist-stats\",\"httpMethod\":\"GET\",\"endpointPattern\":\"/api/v1/demo/profile\",\"tier\":\"FREE\","
                        + "\"keyBy\":\"USER\",\"algorithm\":\"TOKEN_BUCKET\",\"limit\":1000,\"windowMs\":86400000,\"refillTokens\":1}").body();
        policyId = JSON.readTree(body).get("id").asLong();
        for (AppInstance app : apps) {
            await().until(() -> app.context().getBean(PolicyCache.class).snapshot().policies().stream().anyMatch(p -> p.id() == policyId));
        }
    }

    @AfterAll
    static void stop() {
        if (apps != null) {
            apps.forEach(AppInstance::close);
        }
        MYSQL.stop();
        REDIS.stop();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stats(AppInstance via) throws Exception {
        return JSON.readValue(via.get("/api/v1/policies/" + policyId + "/stats", via.tokens().admin()).body(), Map.class);
    }

    private static double rawCounter(String result) {
        double total = 0;
        for (AppInstance app : apps) {
            total += new PrometheusText(app.get("/actuator/prometheus", null).body())
                    .sum("ratelimiter_requests_total", "algorithm", "token_bucket", "result", result);
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static long instanceField(Map<String, Object> stats, String instance, String field) {
        return ((List<Map<String, Object>>) stats.get("instances")).stream().filter(i -> i.get("instanceId").equals(instance))
                .mapToLong(i -> ((Number) i.get(field)).longValue()).sum();
    }

    @Test
    void statsAreExactAcrossInstancesAndSurviveARedisOutageDuringFlush() throws Exception {
        int[] perInstance = {7, 11, 13};
        String user = apps.get(0).tokens().user("dist-stats-user", Tier.FREE);
        double rawAllowedBefore = rawCounter("allowed");
        double rawFailOpenBefore = rawCounter("fail_open");

        for (int i = 0; i < 3; i++) {
            for (int n = 0; n < perInstance[i]; n++) {
                assertThat(apps.get(i).get("/api/v1/demo/profile", user).statusCode()).isEqualTo(200);
            }
        }
        // Read the raw counters now: the admin /stats calls below are themselves rate-limited requests.
        assertThat(rawCounter("allowed") - rawAllowedBefore).as("raw counters of the 3 instances, summed").isEqualTo(31);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> s = stats(apps.get(1));
            assertThat(s).containsEntry("allowed", 31).containsEntry("rejected", 0).containsEntry("failOpen", 0);
            assertThat(instanceField(s, "app-1", "allowed")).isEqualTo(7);
            assertThat(instanceField(s, "app-2", "allowed")).isEqualTo(11);
            assertThat(instanceField(s, "app-3", "allowed")).isEqualTo(13);
        });

        // Outage: requests fail open (counted as fail_open) and flushes time out while Redis is paused.
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (AppInstance app : apps) {
                for (int n = 0; n < 5; n++) {
                    assertThat(app.get("/api/v1/demo/profile", user).statusCode()).isEqualTo(200);
                }
            }
            Thread.sleep(2_500); // at least two flush attempts per instance fail while paused
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(rawCounter("fail_open") - rawFailOpenBefore).isEqualTo(15);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<String, Object> s = stats(apps.get(2));
            assertThat(s).containsEntry("allowed", 31).containsEntry("failOpen", 15).containsEntry("rejected", 0);
            for (String instance : List.of("app-1", "app-2", "app-3")) {
                assertThat(instanceField(s, instance, "failOpen")).as(instance).isEqualTo(5);
            }
        });
        Thread.sleep(2_500); // further flushes must not change the totals (no late double counting)
        assertThat(stats(apps.get(0))).containsEntry("allowed", 31).containsEntry("failOpen", 15);
    }
}
