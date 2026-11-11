package com.utkarsh.ratelimiter.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import com.utkarsh.ratelimiter.policy.RateLimitTarget;
import com.utkarsh.ratelimiter.redis.RateLimitStoreUnavailableException;
import com.utkarsh.ratelimiter.support.AppInstance;
import com.utkarsh.ratelimiter.support.InfrastructureContainers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * The Redis circuit breaker in a real application instance against a real Redis container that is
 * paused (no replies: every call hits the 50 ms command timeout) and unpaused. Production breaker
 * settings: window 20, minimum 10, 50 %, OPEN 5 s, 3 probes, 2 s probe timeout.
 *
 * <p>"Redis was not called" is checked with Redis's own counters ({@code INFO commandstats}), not the app's.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerIT {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(InfrastructureContainers.REDIS_IMAGE).withExposedPorts(6379);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(InfrastructureContainers.MYSQL_IMAGE).withDatabaseName("ratelimiter");
    private static AppInstance app;

    @BeforeAll
    static void start() {
        REDIS.start();
        MYSQL.start();
        app = AppInstance.start(MYSQL, REDIS, "breaker-it");
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        MYSQL.stop();
        REDIS.stop();
    }

    @BeforeEach
    void healthyRedisAndClosedBreaker() throws Exception {
        if (REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getPaused()) {
            unpauseRedis();
        }
        redisCli("FLUSHDB");
        breaker(app).reset();
    }

    // ---------- helpers ----------

    private static CircuitBreaker breaker(AppInstance instance) {
        return instance.context().getBean(CircuitBreaker.class);
    }

    private static double counter(AppInstance instance, String name, String... tags) {
        Counter c = instance.context().getBean(MeterRegistry.class).find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static String redisCli(String... command) throws Exception {
        List<String> args = new ArrayList<>(List.of("redis-cli"));
        args.addAll(List.of(command));
        return REDIS.execInContainer(args.toArray(String[]::new)).getStdout();
    }

    /** Calls Redis has executed for a command, from INFO commandstats (e.g. "evalsha"). */
    private static long redisCalls(String command) throws Exception {
        Matcher m = Pattern.compile("cmdstat_" + command + ":calls=(\\d+)").matcher(redisCli("INFO", "commandstats"));
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static boolean hasRateLimitHeaders(HttpResponse<String> response) {
        return response.headers().firstValue("X-RateLimit-Limit").isPresent();
    }

    private static long percentile(List<Long> sortedMicros, double p) {
        return sortedMicros.get(Math.min(sortedMicros.size() - 1, (int) Math.ceil(p * sortedMicros.size()) - 1));
    }

    /** With Redis paused, sends requests until the breaker opens; returns each request's latency (ms). */
    private static List<Long> tripOpen(AppInstance instance, String token) {
        List<Long> latencies = new ArrayList<>();
        while (breaker(instance).getState() != CircuitBreaker.State.OPEN) {
            long t = System.nanoTime();
            instance.get("/api/v1/demo/profile", token);
            latencies.add((System.nanoTime() - t) / 1_000_000);
            assertThat(latencies).as("breaker should open within 20 failed calls").hasSizeLessThan(25);
        }
        return latencies;
    }

    // ---------- tests ----------

    @Test
    @Order(1)
    void outageOpensTheBreakerRedisIsNotCalledWhileOpenAndItRecoversAutomatically() throws Exception {
        String freeUser = app.tokens().user("cb-" + UUID.randomUUID(), Tier.FREE);
        String enterpriseUser = app.tokens().user("cb-ent-" + UUID.randomUUID(), Tier.ENTERPRISE);
        assertThat(hasRateLimitHeaders(app.get("/api/v1/demo/profile", freeUser))).isTrue();

        // 1. Outage: requests pay the 50 ms timeout until the breaker opens.
        pauseRedis();
        List<Long> beforeOpen = tripOpen(app, freeUser);
        long openedAt = System.nanoTime();
        // The warm-up success above is also a recorded call: opening needs >= 10 recorded calls in total.
        assertThat(beforeOpen.size() + 1).isGreaterThanOrEqualTo(10);

        // Readiness unaffected (R1); dependency health shows the outage and the open breaker.
        assertThat(app.get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        HttpResponse<String> dependencies = app.get("/actuator/health/dependencies", null);
        assertThat(dependencies.statusCode()).isEqualTo(503);
        assertThat(dependencies.body()).contains("\"circuitBreaker\":\"OPEN\"").containsPattern("\"redis\":\\{\"status\":\"DOWN\"");

        // 2. Unpause so Redis's own counters can be read; the breaker stays OPEN for 5 s regardless.
        unpauseRedis();
        Thread.sleep(300); // let the commands that timed out client-side (still buffered) execute
        long evalshaBefore = redisCalls("evalsha");

        // 3a. In-process limiter decision while OPEN (no HTTP): policy lookup + registry + breaker refusal.
        PolicyResolver resolver = app.context().getBean(PolicyResolver.class);
        AlgorithmRegistry registry = app.context().getBean(AlgorithmRegistry.class);
        RateLimitTarget target = resolver.resolve(HttpMethod.GET, "/api/v1/demo/profile",
                new ClientIdentity("in-process", Tier.FREE, "203.0.113.9")).orElseThrow();
        List<Long> decisionMicros = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            long t = System.nanoTime();
            assertThatThrownBy(() -> registry.tryAcquire(target.key(), target.policy().rule()))
                    .isInstanceOf(RateLimitStoreUnavailableException.class);
            decisionMicros.add((System.nanoTime() - t) / 1_000);
        }
        Collections.sort(decisionMicros);

        // 3b. Over HTTP while OPEN: fail-open endpoint and fail-closed endpoint.
        List<Long> httpMicros = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long t = System.nanoTime();
            HttpResponse<String> response = app.get("/api/v1/demo/profile", freeUser);
            httpMicros.add((System.nanoTime() - t) / 1_000);
            assertThat(response.statusCode()).isEqualTo(200);                // free-tier: FAIL_OPEN (global default)
            assertThat(hasRateLimitHeaders(response)).isFalse();
        }
        Collections.sort(httpMicros);
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> closed = app.get("/api/v1/demo/enterprise", enterpriseUser); // FAIL_CLOSED policy
            assertThat(closed.statusCode()).isEqualTo(503);
            assertThat(closed.headers().firstValue("Retry-After")).contains("5");
            assertThat(closed.body()).contains("\"status\":503").doesNotContain("\"status\":429");
        }

        // 4. Still OPEN, and Redis executed no rate-limit script during all of the above.
        assertThat(breaker(app).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long evalshaAfter = redisCalls("evalsha");
        long evalAfter = redisCalls("eval");
        assertThat(evalshaAfter).as("EVALSHA calls while OPEN").isEqualTo(evalshaBefore);
        double openPhaseSeconds = (System.nanoTime() - openedAt) / 1e9;

        // 5. Recovery: the first request after 5 s probes; 3 successful probes close the breaker.
        long recoveryStart = System.nanoTime();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
            app.get("/api/v1/demo/profile", freeUser);
            return breaker(app).getState() == CircuitBreaker.State.CLOSED;
        });
        double openToClosedSeconds = (System.nanoTime() - openedAt) / 1e9;
        assertThat(hasRateLimitHeaders(app.get("/api/v1/demo/profile", app.tokens().user("after-" + UUID.randomUUID(), Tier.FREE)))).isTrue();
        assertThat(counter(app, "ratelimiter.circuit.breaker.transitions", "from", "closed", "to", "open")).isEqualTo(1);
        assertThat(counter(app, "ratelimiter.circuit.breaker.transitions", "from", "open", "to", "half_open")).isEqualTo(1);
        assertThat(counter(app, "ratelimiter.circuit.breaker.transitions", "from", "half_open", "to", "closed")).isEqualTo(1);
        assertThat(app.get("/actuator/health/dependencies", null).statusCode()).isEqualTo(200);

        System.out.printf("[breaker] requests before OPEN: %d, their latency ms: %s%n", beforeOpen.size(), beforeOpen);
        System.out.printf("[breaker] in-process decision while OPEN (1000 calls): p50=%d us p99=%d us max=%d us%n",
                percentile(decisionMicros, 0.50), percentile(decisionMicros, 0.99), decisionMicros.get(decisionMicros.size() - 1));
        System.out.printf("[breaker] HTTP latency while OPEN (200 req): p50=%d us p99=%d us max=%d us%n",
                percentile(httpMicros, 0.50), percentile(httpMicros, 0.99), httpMicros.get(httpMicros.size() - 1));
        System.out.printf("[breaker] EVALSHA calls during OPEN phase: before=%d after=%d (EVAL total %d); OPEN phase measured %.2f s%n",
                evalshaBefore, evalshaAfter, evalAfter, openPhaseSeconds);
        System.out.printf("[breaker] OPEN -> CLOSED after %.2f s (recovery polling started %.2f s after unpause)%n",
                openToClosedSeconds, (recoveryStart - openedAt) / 1e9);

        assertThat(percentile(decisionMicros, 0.99)).as("limiter decision P99 while OPEN, microseconds").isLessThan(5_000);
    }

    @Test
    @Order(2)
    void aFailedProbeReopensAndRestartsTheWait() throws Exception {
        String user = app.tokens().user("flap-" + UUID.randomUUID(), Tier.FREE);
        pauseRedis();
        tripOpen(app, user);
        Thread.sleep(5_200);

        // Still paused: the first request becomes a probe and fails -> OPEN again at once.
        app.get("/api/v1/demo/profile", user);
        assertThat(breaker(app).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(counter(app, "ratelimiter.circuit.breaker.transitions", "from", "half_open", "to", "open")).isGreaterThanOrEqualTo(1);

        // The wait restarted: an immediate request is refused without a probe.
        double refusedBefore = counter(app, "ratelimiter.redis.errors", "type", "circuit_open");
        long t = System.nanoTime();
        app.get("/api/v1/demo/profile", user);
        assertThat((System.nanoTime() - t) / 1_000_000).as("no 50 ms probe").isLessThan(40);
        assertThat(counter(app, "ratelimiter.redis.errors", "type", "circuit_open")).isEqualTo(refusedBefore + 1);
    }

    @Test
    @Order(3)
    void exactlyThreeOfFiftyConcurrentRequestsBecomeProbesWhileProbesAreInFlight() throws Exception {
        // Redis stays paused when probing starts, so the 3 probes are in flight (until the 50 ms timeout) while
        // the other 47 arrive. Redis's own EVALSHA counter, read after unpausing, shows how many reached it.
        // (With a healthy Redis the probes finish in ~1 ms and the breaker closes before the burst is over:
        // closing is verified in test 1.)
        pauseRedis();
        tripOpen(app, app.tokens().user("trip-" + UUID.randomUUID(), Tier.FREE));
        long openedAt = System.nanoTime();
        unpauseRedis();
        Thread.sleep(300);
        long evalshaBefore = redisCalls("evalsha");
        pauseRedis();
        Thread.sleep(Math.max(0, 5_200 - (System.nanoTime() - openedAt) / 1_000_000));

        String user = app.tokens().user("probe-" + UUID.randomUUID(), Tier.FREE);
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch go = new CountDownLatch(1);
        Queue<long[]> results = new ConcurrentLinkedQueue<>(); // {status, millis}
        CountDownLatch done = new CountDownLatch(50);
        for (int i = 0; i < 50; i++) {
            pool.execute(() -> {
                try {
                    go.await();
                    long t = System.nanoTime();
                    int status = app.get("/api/v1/demo/profile", user).statusCode();
                    results.add(new long[] {status, (System.nanoTime() - t) / 1_000_000});
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        double openEvents = counter(app, "ratelimiter.circuit.breaker.transitions", "from", "half_open", "to", "open");

        unpauseRedis();
        Thread.sleep(300); // the probes' buffered commands now execute
        long evalshaDelta = redisCalls("evalsha") - evalshaBefore;
        long slow = results.stream().filter(r -> r[1] >= 45).count();
        System.out.printf("[breaker] 50 concurrent requests at HALF_OPEN: EVALSHA reaching Redis=%d, requests that waited "
                + ">=45 ms (probes)=%d, statuses=%s%n", evalshaDelta, slow, results.stream().map(r -> r[0]).distinct().toList());

        assertThat(evalshaDelta).as("probes that reached Redis").isEqualTo(3);
        assertThat(results).allMatch(r -> r[0] == 200); // FAIL_OPEN for probes that failed and for refused requests
        assertThat(breaker(app).getState()).as("the in-flight probes failed").isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(openEvents).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    void scriptCacheLossAfterARedisRestartRecoversThroughEvalWithoutTrippingTheBreaker() throws Exception {
        String user = app.tokens().user("noscript-" + UUID.randomUUID(), Tier.FREE);
        assertThat(app.get("/api/v1/demo/profile", user).headers().firstValue("X-RateLimit-Remaining")).contains("19");
        double failuresBefore = breaker(app).getMetrics().getNumberOfFailedCalls();
        long evalBefore = redisCalls("eval");

        redisCli("SCRIPT", "FLUSH");                                   // script cache lost
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = app.get("/api/v1/demo/profile", user);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-RateLimit-Remaining")).contains(Integer.toString(18 - i));
        }
        assertThat(redisCalls("eval")).as("NOSCRIPT answered by falling back to EVAL").isGreaterThan(evalBefore);

        redisCli("FLUSHALL");                                          // what a restart without persistence leaves:
        redisCli("SCRIPT", "FLUSH");                                   // no data and no scripts
        assertThat(app.get("/api/v1/demo/profile", user).headers().firstValue("X-RateLimit-Remaining"))
                .as("quota restarts from full after a Redis restart (documented)").contains("19");

        assertThat(breaker(app).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker(app).getMetrics().getNumberOfFailedCalls()).isEqualTo((int) failuresBefore);
    }

    @Test
    @Order(5)
    void poolExhaustionIsAnInfrastructureFailureThatOpensTheBreaker() throws Exception {
        AppInstance pooled = AppInstance.start(MYSQL, REDIS, "pooled",
                "--spring.data.redis.lettuce.pool.enabled=true",
                "--spring.data.redis.lettuce.pool.max-active=1",
                "--spring.data.redis.lettuce.pool.max-idle=1",
                "--spring.data.redis.lettuce.pool.max-wait=20ms");
        try {
            String user = pooled.tokens().user("pool-" + UUID.randomUUID(), Tier.FREE);
            assertThat(hasRateLimitHeaders(pooled.get("/api/v1/demo/profile", user))).as("pool mode works when healthy").isTrue();

            pauseRedis();
            ExecutorService pool = Executors.newFixedThreadPool(20);
            List<java.util.concurrent.Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> pooled.get("/api/v1/demo/profile", user)));
            }
            for (var f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS).statusCode()).as("FAIL_OPEN").isEqualTo(200);
            }
            pool.shutdown();

            double poolErrors = counter(pooled, "ratelimiter.redis.errors", "type", "pool");
            System.out.printf("[breaker] pool mode, Redis paused, 40 requests on 20 threads: pool=%.0f timeout=%.0f circuit_open=%.0f, state=%s%n",
                    poolErrors, counter(pooled, "ratelimiter.redis.errors", "type", "timeout"),
                    counter(pooled, "ratelimiter.redis.errors", "type", "circuit_open"), breaker(pooled).getState());
            assertThat(poolErrors).isGreaterThan(0);
            assertThat(breaker(pooled).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        } finally {
            unpauseRedis();
            pooled.close();
        }
    }

    @Test
    @Order(6)
    void mysqlOutageAtRuntimeDoesNotAffectReadinessOrRateLimiting() throws Exception {
        MYSQL.stop(); // last test: MySQL is gone for good
        String user = app.tokens().user("nomysql-" + UUID.randomUUID(), Tier.FREE);

        assertThat(app.get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        HttpResponse<String> dependencies = app.get("/actuator/health/dependencies", null);
        assertThat(dependencies.statusCode()).isEqualTo(503);
        assertThat(dependencies.body()).containsPattern("\"db\":\\{\"status\":\"DOWN\"");
        HttpResponse<String> limited = app.get("/api/v1/demo/profile", user);
        assertThat(limited.statusCode()).isEqualTo(200);
        assertThat(limited.headers().firstValue("X-RateLimit-Limit")).contains("20"); // last good policy snapshot
    }
}
