package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.redis.CircuitBreakingScriptExecutor;
import com.utkarsh.ratelimiter.redis.RateLimitScript;
import com.utkarsh.ratelimiter.redis.RateLimitScriptExecutor;
import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.redis.RateLimitStoreUnavailableException;
import com.utkarsh.ratelimiter.resilience.CircuitBreakerConfiguration;
import com.utkarsh.ratelimiter.resilience.CircuitBreakerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.PoolException;

/**
 * The project's circuit-breaker rules on top of a real Resilience4j breaker, with the production
 * configuration (window 20, minimum 10, 50 %, 3 probes) except a 300 ms open wait to keep tests fast.
 * Probe-timeout tests drive a fake nanosecond clock so the 2 s timeout is exercised without waiting.
 */
class CircuitBreakingScriptExecutorTest {

    private static final List<Long> ALLOWED = List.of(1L, 9L, 0L, 1_000L);
    private static final List<Long> REJECTED = List.of(0L, 0L, 1_000L, 1_000L); // a 429: Redis answered
    private static final Duration WAIT = Duration.ofMillis(300);

    private final CircuitBreakerProperties properties =
            new CircuitBreakerProperties(true, 20, 10, 50, WAIT, 3, Duration.ofSeconds(2));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfiguration.breakerConfig(properties));
    private final CircuitBreaker breaker;
    private final AtomicLong fakeNanos = new AtomicLong(1_000_000_000L);
    private final AtomicInteger delegateCalls = new AtomicInteger();
    private volatile Supplier<List<Long>> behaviour = () -> ALLOWED;
    private final CircuitBreakingScriptExecutor executor;

    CircuitBreakingScriptExecutorTest() {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        breaker = CircuitBreakerConfiguration.instrument(registry.circuitBreaker("test"), meters);
        RateLimitScriptExecutor delegate = (script, key, args) -> {
            delegateCalls.incrementAndGet();
            return behaviour.get();
        };
        executor = new CircuitBreakingScriptExecutor(delegate, breaker, properties.maxWaitInHalfOpen(), meters, fakeNanos::get);
    }

    // ---------- helpers ----------

    private static RuntimeException timeout() {
        return new RateLimitStoreException("Redis script failed", new QueryTimeoutException("Redis command timed out"));
    }

    private static RuntimeException programmingError() {
        return new RateLimitStoreException("Redis script failed", new RedisSystemException("Error in execution",
                new io.lettuce.core.RedisCommandExecutionException("ERR Error running script: attempt to compare nil with number")));
    }

    private Throwable call() {
        try {
            executor.execute(RateLimitScript.TOKEN_BUCKET, "k", List.of("1"));
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void failing(Supplier<RuntimeException> error) {
        behaviour = () -> {
            throw error.get();
        };
    }

    private void open() {
        failing(CircuitBreakingScriptExecutorTest::timeout);
        for (int i = 0; i < 10; i++) {
            call();
        }
        assertThat(breaker.getState()).isEqualTo(State.OPEN);
    }

    /** OPEN, then the wait elapses; the next call will become probe 1. */
    private void openAndWait() throws InterruptedException {
        open();
        Thread.sleep(WAIT.toMillis() + 50);
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---------- CLOSED ----------

    @Test
    void staysClosedBelowTheMinimumNumberOfCalls() {
        failing(CircuitBreakingScriptExecutorTest::timeout);
        for (int i = 0; i < 9; i++) {
            assertThat(call()).isInstanceOf(RateLimitStoreException.class);
        }
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void opensAtFiftyPercentInfrastructureFailures() {
        for (int i = 0; i < 5; i++) {
            call();
        }
        failing(CircuitBreakingScriptExecutorTest::timeout);
        for (int i = 0; i < 4; i++) {
            call();
        }
        assertThat(breaker.getState()).as("4/9 recorded").isEqualTo(State.CLOSED);
        call();
        assertThat(breaker.getState()).as("5/10 = 50 %").isEqualTo(State.OPEN);
        assertThat(count("ratelimiter.circuit.breaker.transitions", "from", "closed", "to", "open")).isEqualTo(1);
    }

    @Test
    void rejectedRateLimitResultsAreSuccesses() {
        behaviour = () -> REJECTED;
        for (int i = 0; i < 100; i++) {
            assertThat(call()).isNull();
        }
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getMetrics().getFailureRate()).isZero();
    }

    @Test
    void programmingErrorsNeverTripTheBreakerAndStillPropagate() {
        failing(CircuitBreakingScriptExecutorTest::programmingError);
        for (int i = 0; i < 100; i++) {
            assertThat(call()).isInstanceOf(RateLimitStoreException.class).isNotInstanceOf(RateLimitStoreUnavailableException.class);
        }
        failing(() -> new RateLimitStoreException("Redis script TOKEN_BUCKET returned non-integer element: x"));
        for (int i = 0; i < 100; i++) {
            call();
        }
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(count("ratelimiter.redis.ignored.errors")).isEqualTo(200);
    }

    // ---------- OPEN ----------

    @Test
    void openNeverCallsRedis() {
        open();
        int before = delegateCalls.get();
        for (int i = 0; i < 100; i++) {
            assertThat(call()).isInstanceOf(RateLimitStoreUnavailableException.class).hasMessageContaining("OPEN");
        }
        assertThat(delegateCalls.get()).isEqualTo(before);
        assertThat(count("ratelimiter.redis.errors", "type", "circuit_open")).isEqualTo(100);
    }

    // ---------- HALF_OPEN: 3 of 3 ----------

    @Test
    void threeSuccessfulProbesCloseAndTwoAreNotEnough() throws InterruptedException {
        openAndWait();
        behaviour = () -> ALLOWED;
        int before = delegateCalls.get();

        assertThat(call()).isNull();
        assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);
        assertThat(call()).isNull();
        assertThat(breaker.getState()).as("2 of 3").isEqualTo(State.HALF_OPEN);
        assertThat(call()).isNull();
        assertThat(breaker.getState()).as("3 of 3").isEqualTo(State.CLOSED);
        assertThat(delegateCalls.get() - before).isEqualTo(3);
        assertThat(count("ratelimiter.circuit.breaker.transitions", "from", "open", "to", "half_open")).isEqualTo(1);
        assertThat(count("ratelimiter.circuit.breaker.transitions", "from", "half_open", "to", "closed")).isEqualTo(1);
    }

    @Test
    void aFailingThirdProbeReopensWhereTheLibraryAloneWouldClose() throws InterruptedException {
        openAndWait();
        behaviour = () -> ALLOWED;
        call();
        call();
        failing(CircuitBreakingScriptExecutorTest::timeout);
        call(); // 2 successes + 1 failure = 33 % < 50 %: Resilience4j by itself would CLOSE here

        assertThat(breaker.getState()).isEqualTo(State.OPEN);
        assertThat(count("ratelimiter.circuit.breaker.transitions", "from", "half_open", "to", "closed")).isZero();
    }

    @Test
    void aFailedFirstProbeReopensImmediatelyAndRestartsTheWait() throws InterruptedException {
        openAndWait();
        failing(CircuitBreakingScriptExecutorTest::timeout);
        call();
        assertThat(breaker.getState()).isEqualTo(State.OPEN);

        behaviour = () -> ALLOWED;
        int before = delegateCalls.get();
        assertThat(call()).isInstanceOf(RateLimitStoreUnavailableException.class); // new wait in progress
        assertThat(delegateCalls.get()).isEqualTo(before);
    }

    @Test
    void probeWithAProgrammingErrorReturnsItsPermit() throws InterruptedException {
        openAndWait();
        failing(CircuitBreakingScriptExecutorTest::programmingError);
        call();
        assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);

        behaviour = () -> ALLOWED;
        call();
        call();
        assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);
        call();
        assertThat(breaker.getState()).as("closes only after 3 genuine successes").isEqualTo(State.CLOSED);
    }

    // ---------- HALF_OPEN: probe timeout applies to one in-flight probe (R5 option B) ----------

    @Test
    void quietTrafficDoesNotReopenTheBreaker() throws InterruptedException {
        openAndWait();
        behaviour = () -> ALLOWED;
        call();                                            // probe 1 at t
        fakeNanos.addAndGet(Duration.ofSeconds(5).toNanos()); // nothing arrives for 5 s (> 2 s)
        assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);
        call();
        call();
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void aHungProbeReopensTheBreakerAndItsLateSuccessIsNeverRecorded() throws Exception {
        openAndWait();
        CountDownLatch release = new CountDownLatch(1);
        behaviour = () -> {
            await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
            return ALLOWED;
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var hung = pool.submit(this::call);                // probe 1 hangs
        await().until(() -> delegateCalls.get() > 10);
        fakeNanos.addAndGet(Duration.ofMillis(2_100).toNanos());

        behaviour = () -> ALLOWED;
        assertThat(call()).as("guard reopens before this caller is admitted").isInstanceOf(RateLimitStoreUnavailableException.class);
        assertThat(breaker.getState()).isEqualTo(State.OPEN);

        release.countDown();
        assertThat(hung.get(5, TimeUnit.SECONDS)).isNull(); // the caller still gets its result
        assertThat(breaker.getState()).as("late success not recorded").isEqualTo(State.OPEN);
        pool.shutdownNow();
    }

    @Test
    void aProbeThatTakesLongerThanTheTimeoutReopensOnCompletion() throws InterruptedException {
        openAndWait();
        behaviour = () -> {
            fakeNanos.addAndGet(Duration.ofMillis(2_500).toNanos()); // the call itself takes 2.5 s
            return ALLOWED;
        };
        call();
        assertThat(breaker.getState()).isEqualTo(State.OPEN);
    }

    // ---------- concurrency ----------

    private static List<Throwable> concurrently(int threads, Supplier<Throwable> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Queue<Throwable> results = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    Throwable result = task.get();
                    results.add(result == null ? new Throwable("OK") : result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        return new ArrayList<>(results);
    }

    @Test
    void exactlyThreeOfOneHundredConcurrentRequestsBecomeProbes() throws InterruptedException {
        openAndWait();
        behaviour = () -> {
            sleep(20); // probes are in flight while the other 97 arrive
            return ALLOWED;
        };
        int before = delegateCalls.get();

        List<Throwable> results = concurrently(100, this::call);

        assertThat(delegateCalls.get() - before).isEqualTo(3);
        assertThat(results).filteredOn(r -> r instanceof RateLimitStoreUnavailableException).hasSize(97);
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void aFailingProbeAmongConcurrentProbesNeverLetsTheBreakerClose() throws InterruptedException {
        for (int round = 0; round < 50; round++) {
            CircuitBreakingScriptExecutorTest fresh = new CircuitBreakingScriptExecutorTest();
            fresh.openAndWait();
            AtomicInteger probeNumber = new AtomicInteger();
            int failingProbe = round % 3;                   // vary which probe fails
            long failDelay = round % 2 == 0 ? 0 : 15;       // and whether it finishes first or last
            fresh.behaviour = () -> {
                int n = probeNumber.getAndIncrement();
                if (n == failingProbe) {
                    sleep(failDelay);
                    throw timeout();
                }
                sleep(15 - failDelay);
                return ALLOWED;
            };
            concurrently(3, fresh::call);

            assertThat(fresh.breaker.getState()).as("round %d", round).isEqualTo(State.OPEN);
            assertThat(fresh.count("ratelimiter.circuit.breaker.transitions", "from", "half_open", "to", "closed"))
                    .as("round %d", round).isZero();
        }
    }

    @Test
    void failureStormIsCutOffOnceTheBreakerOpens() throws InterruptedException {
        // 50 worker threads (a Tomcat-like pool) push 1,000 requests at a Redis that times out.
        failing(() -> {
            sleep(5);
            return timeout();
        });
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch done = new CountDownLatch(1_000);
        for (int i = 0; i < 1_000; i++) {
            pool.execute(() -> {
                call();
                done.countDown();
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(breaker.getState()).isEqualTo(State.OPEN);
        // At most the 10 recorded failures that opened it plus the 50 requests in flight at that moment.
        assertThat(delegateCalls.get()).isLessThanOrEqualTo(60);
        assertThat(count("ratelimiter.redis.errors", "type", "circuit_open")).isGreaterThanOrEqualTo(940);
    }

    // ---------- metrics ----------

    @Test
    void infrastructureErrorsAreCountedByType() {
        failing(CircuitBreakingScriptExecutorTest::timeout);
        call();
        failing(() -> new RateLimitStoreException("x", new RedisConnectionFailureException("refused")));
        call();
        failing(() -> new RateLimitStoreException("x", new PoolException("Could not get a resource from the pool")));
        call();
        failing(() -> new RateLimitStoreException("x", new RedisSystemException("e",
                new io.lettuce.core.RedisCommandExecutionException("OOM command not allowed"))));
        call();

        assertThat(count("ratelimiter.redis.errors", "type", "timeout")).isEqualTo(1);
        assertThat(count("ratelimiter.redis.errors", "type", "connection")).isEqualTo(1);
        assertThat(count("ratelimiter.redis.errors", "type", "pool")).isEqualTo(1);
        assertThat(count("ratelimiter.redis.errors", "type", "server")).isEqualTo(1);
        assertThat(meters.find("resilience4j.circuitbreaker.state").tags("name", "test", "state", "closed").gauge().value()).isEqualTo(1);
        assertThat(meters.find("resilience4j.circuitbreaker.calls").tags("kind", "failed").timer().count()).isEqualTo(4);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
