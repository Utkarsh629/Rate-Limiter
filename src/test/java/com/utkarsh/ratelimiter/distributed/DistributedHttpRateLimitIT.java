package com.utkarsh.ratelimiter.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.PolicyCache;
import com.utkarsh.ratelimiter.support.AppInstance;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * Three real application instances share one Redis and one MySQL. Concurrent HTTP requests from one
 * identity are spread round-robin across the instances; the total number of HTTP 200s must equal the
 * quota exactly, for every algorithm. No load balancer: each request goes straight to an instance's port.
 */
class DistributedHttpRateLimitIT {

    private static final int QUOTA = 50;
    private static final int REQUESTS = 300;
    private static final int CLIENT_THREADS = 60;

    private static final GenericContainer<?> REDIS = new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE).withExposedPorts(6379);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(InfrastructureContainers.MYSQL_IMAGE).withDatabaseName("ratelimiter");
    private static List<AppInstance> instances;
    private static long policyId;
    private static int policyVersion;

    @BeforeAll
    static void startCluster() {
        REDIS.start();
        MYSQL.start();
        instances = List.of(
                AppInstance.start(MYSQL, REDIS, "app-1"),
                AppInstance.start(MYSQL, REDIS, "app-2"),
                AppInstance.start(MYSQL, REDIS, "app-3"));
        // A dedicated FREE-tier policy for /profile with negligible refill (1 token/day) so the quota is exact.
        HttpResponse<String> created = instances.get(0).send("POST", "/api/v1/policies", instances.get(0).tokens().admin(),
                policyJson("TOKEN_BUCKET", null));
        assertThat(created.statusCode()).isEqualTo(201);
        policyId = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        policyVersion = 0;
        awaitEverywhere("TOKEN_BUCKET");
    }

    @AfterAll
    static void stopCluster() {
        if (instances != null) {
            instances.forEach(AppInstance::close);
        }
        MYSQL.stop();
        REDIS.stop();
    }

    private static String policyJson(String algorithm, Integer version) {
        String refill = algorithm.equals("TOKEN_BUCKET") ? ",\"refillTokens\":1" : "";
        String versionField = version == null ? "" : ",\"version\":" + version;
        return "{\"name\":\"it-distributed\",\"httpMethod\":\"GET\",\"endpointPattern\":\"/api/v1/demo/profile\","
                + "\"tier\":\"FREE\",\"keyBy\":\"USER\",\"algorithm\":\"" + algorithm + "\",\"limit\":" + QUOTA
                + ",\"windowMs\":86400000" + refill + versionField + "}";
    }

    private static void awaitEverywhere(String algorithm) {
        for (AppInstance instance : instances) {
            await().atMost(Duration.ofSeconds(5)).until(() -> instance.context().getBean(PolicyCache.class).snapshot().policies()
                    .stream().anyMatch(p -> p.name().equals("it-distributed") && p.rule().algorithm().name().equals(algorithm)));
        }
    }

    private static void switchAlgorithm(String algorithm) {
        if (instances.get(0).context().getBean(PolicyCache.class).snapshot().policies().stream()
                .anyMatch(p -> p.name().equals("it-distributed") && p.rule().algorithm().name().equals(algorithm))) {
            return;
        }
        HttpResponse<String> updated = instances.get(1).send("PUT", "/api/v1/policies/" + policyId,
                instances.get(1).tokens().admin(), policyJson(algorithm, policyVersion));
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        policyVersion++;
        awaitEverywhere(algorithm);
    }

    /** Fires {@code REQUESTS} concurrent GETs round-robin across the instances; returns status -> count, per instance. */
    private static Map<String, AtomicInteger> fire(String path, String token) throws InterruptedException {
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(CLIENT_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUESTS);
        for (int i = 0; i < REQUESTS; i++) {
            int instance = i % instances.size();
            pool.execute(() -> {
                try {
                    start.await();
                    int status = instances.get(instance).get(path, token).statusCode();
                    counts.computeIfAbsent(status + "@app-" + (instance + 1), k -> new AtomicInteger()).incrementAndGet();
                    counts.computeIfAbsent(Integer.toString(status), k -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(errors).isEmpty();
        return counts;
    }

    private static int count(Map<String, AtomicInteger> counts, String key) {
        AtomicInteger value = counts.get(key);
        return value == null ? 0 : value.get();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TOKEN_BUCKET", "FIXED_WINDOW", "SLIDING_WINDOW"})
    void oneUserAcrossThreeInstancesNeverExceedsTheQuota(String algorithm) throws InterruptedException {
        switchAlgorithm(algorithm);
        String token = instances.get(0).tokens().user("dist-" + algorithm.toLowerCase(), Tier.FREE);

        Map<String, AtomicInteger> counts = fire("/api/v1/demo/profile", token);

        System.out.printf("[distributed %s] %s%n", algorithm, new java.util.TreeMap<>(counts));
        assertThat(count(counts, "200")).as("accepted across 3 instances").isEqualTo(QUOTA);
        assertThat(count(counts, "429")).as("rejected").isEqualTo(REQUESTS - QUOTA);
        assertThat(count(counts, "200") + count(counts, "429")).as("no other statuses").isEqualTo(REQUESTS);
        for (int i = 1; i <= 3; i++) {
            assertThat(count(counts, "200@app-" + i) + count(counts, "429@app-" + i)).as("app-%d served its share", i)
                    .isEqualTo(REQUESTS / 3);
        }
    }

    @Test
    void breakersOpenAndRecoverIndependentlyAndTheSharedQuotaHoldsAfterRecovery() throws Exception {
        // Tripping uses a throwaway FREE user: the timed-out commands execute when Redis resumes and
        // would otherwise consume the anonymous per-IP quota used by another test.
        String tripUser = instances.get(0).tokens().user("trip-" + System.nanoTime(), Tier.FREE);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (AppInstance instance : instances) {
                int attempts = 0;
                while (breakerOf(instance).getState() != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN) {
                    instance.get("/api/v1/demo/profile", tripUser);
                    assertThat(++attempts).isLessThan(25);
                }
            }
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        Thread.sleep(5_300);

        // Only app-1 receives traffic: it probes and closes; app-2 and app-3 stay OPEN (lazy, no traffic).
        String probeUser = instances.get(0).tokens().user("probe-" + System.nanoTime(), Tier.FREE);
        for (int i = 0; i < 3; i++) {
            instances.get(0).get("/api/v1/demo/profile", probeUser);
        }
        System.out.printf("[distributed breaker] after app-1 probed: app-1=%s app-2=%s app-3=%s%n",
                breakerOf(instances.get(0)).getState(), breakerOf(instances.get(1)).getState(), breakerOf(instances.get(2)).getState());
        assertThat(breakerOf(instances.get(0)).getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(breakerOf(instances.get(1)).getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        assertThat(breakerOf(instances.get(2)).getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);

        for (AppInstance instance : instances.subList(1, 3)) {
            for (int i = 0; i < 3; i++) {
                instance.get("/api/v1/demo/profile", probeUser);
            }
            assertThat(breakerOf(instance).getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        }

        // Shared quota still exact across the three recovered instances.
        Map<String, AtomicInteger> counts = fire("/api/v1/demo/profile",
                instances.get(0).tokens().user("after-recovery-" + System.nanoTime(), Tier.FREE));
        System.out.printf("[distributed after recovery] %s%n", new java.util.TreeMap<>(counts));
        assertThat(count(counts, "200")).isEqualTo(QUOTA);
        assertThat(count(counts, "429")).isEqualTo(REQUESTS - QUOTA);
    }

    private static io.github.resilience4j.circuitbreaker.CircuitBreaker breakerOf(AppInstance instance) {
        return instance.context().getBean(io.github.resilience4j.circuitbreaker.CircuitBreaker.class);
    }

    @Test
    void anonymousIpQuotaIsSharedAcrossInstances() throws InterruptedException {
        // anonymous-default: 10 per minute per IP (sliding window); all requests come from loopback.
        Map<String, AtomicInteger> counts = fire("/api/v1/demo/public", null);

        System.out.printf("[distributed anonymous] %s%n", new java.util.TreeMap<>(counts));
        assertThat(count(counts, "200")).isEqualTo(10);
        assertThat(count(counts, "429")).isEqualTo(REQUESTS - 10);
    }
}
