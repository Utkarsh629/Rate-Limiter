package com.utkarsh.ratelimiter.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.FixedWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.utkarsh.ratelimiter.redis.RedisServerClock;
import com.utkarsh.ratelimiter.redis.RedisTemplateScriptExecutor;
import com.utkarsh.ratelimiter.redis.ScriptClock;
import com.utkarsh.ratelimiter.support.ManualScriptClock;
import com.utkarsh.ratelimiter.support.RedisScriptTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 1,000 requests released at the same instant against a limit of 100 must yield exactly 100
 * admissions: never 101 (a race let two requests take the last unit) and never 99 (a lost update).
 *
 * <p>Fixed and sliding window use a frozen test clock so that a real window boundary can never fall
 * inside the test; atomicity, not time, is what is being tested. Token bucket also runs with Redis
 * server time (refill of 1 token per 24 h is negligible for the test's duration).
 */
class ConcurrentAcquisitionIT extends RedisScriptTestSupport {

    private static final int REQUESTS = 1_000;
    private static final int LIMIT = 100;
    private static final int ROUNDS = 5;
    private static final List<LettuceConnectionFactory> EXTRA_CLIENTS = new ArrayList<>();

    private final ManualScriptClock frozen = new ManualScriptClock(T0 + 1_234);

    static RateLimitRule ruleFor(AlgorithmType type) {
        return switch (type) {
            case TOKEN_BUCKET -> RateLimitRule.tokenBucket(LIMIT, 1, Duration.ofHours(24));
            case FIXED_WINDOW -> RateLimitRule.fixedWindow(LIMIT, Duration.ofMinutes(1));
            case SLIDING_WINDOW -> RateLimitRule.slidingWindow(LIMIT, Duration.ofMinutes(1));
        };
    }

    private RateLimitAlgorithm algorithm(AlgorithmType type, StringRedisTemplate client, ScriptClock clock) {
        RedisTemplateScriptExecutor executor = new RedisTemplateScriptExecutor(client);
        return switch (type) {
            case TOKEN_BUCKET -> new TokenBucketAlgorithm(executor, keyFactory, clock);
            case FIXED_WINDOW -> new FixedWindowAlgorithm(executor, keyFactory, clock);
            case SLIDING_WINDOW -> new SlidingWindowAlgorithm(executor, keyFactory, clock);
        };
    }

    @ParameterizedTest
    @EnumSource(AlgorithmType.class)
    void exactlyTheLimitIsAdmittedUnderContention(AlgorithmType type) throws Exception {
        RateLimitAlgorithm algorithm = algorithm(type, redis, frozen);
        RateLimitRule rule = ruleFor(type);

        for (int round = 0; round < ROUNDS; round++) {
            RateLimitKey key = RateLimitKey.user(1, type + "-round-" + round);
            int admitted = runConcurrently(REQUESTS, i -> algorithm.tryAcquire(key, rule).allowed());

            assertThat(admitted).as("%s round %d", type, round).isEqualTo(LIMIT);
            assertConsistentState(type, keyFactory.stateKey(key, type));
        }
    }

    @Test
    void tokenBucketWithRedisServerTimeAdmitsExactlyCapacity() throws Exception {
        RateLimitAlgorithm algorithm = algorithm(AlgorithmType.TOKEN_BUCKET, redis, RedisServerClock.INSTANCE);
        RateLimitRule rule = ruleFor(AlgorithmType.TOKEN_BUCKET);

        for (int round = 0; round < ROUNDS; round++) {
            RateLimitKey key = RateLimitKey.user(1, "server-time-" + round);
            assertThat(runConcurrently(REQUESTS, i -> algorithm.tryAcquire(key, rule).allowed())).isEqualTo(LIMIT);
        }
    }

    @ParameterizedTest
    @EnumSource(AlgorithmType.class)
    void threeIndependentClientsShareOneQuota(AlgorithmType type) throws Exception {
        // Three connection factories = three application instances, each with its own algorithm objects.
        List<RateLimitAlgorithm> instances = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LettuceConnectionFactory factory = newConnectionFactory();
            EXTRA_CLIENTS.add(factory);
            ScriptClock clock = type == AlgorithmType.TOKEN_BUCKET ? RedisServerClock.INSTANCE : frozen;
            instances.add(algorithm(type, new StringRedisTemplate(factory), clock));
        }
        RateLimitRule rule = ruleFor(type);
        RateLimitKey key = RateLimitKey.user(1, "shared-" + type);
        AtomicInteger[] perInstance = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};

        int admitted = runConcurrently(REQUESTS, i -> {
            boolean allowed = instances.get(i % 3).tryAcquire(key, rule).allowed();
            if (allowed) {
                perInstance[i % 3].incrementAndGet();
            }
            return allowed;
        });

        assertThat(admitted).isEqualTo(LIMIT);
        assertThat(perInstance[0].get() + perInstance[1].get() + perInstance[2].get()).isEqualTo(LIMIT);
        assertConsistentState(type, keyFactory.stateKey(key, type));
    }

    @Test
    void negativeControlUnsafeGetThenSetCounterOverAdmits() throws Exception {
        // Proves this harness actually creates contention: the naive non-atomic pattern the Lua
        // scripts replace (read the count, compare in Java, write count + 1) admits far more than
        // the limit when 1,000 requests arrive together.
        String key = "unsafe-counter";
        int admitted = runConcurrently(REQUESTS, i -> {
            String raw = redis.opsForValue().get(key);
            int count = raw == null ? 0 : Integer.parseInt(raw);
            if (count < LIMIT) {
                redis.opsForValue().set(key, Integer.toString(count + 1));
                return true;
            }
            return false;
        });

        System.out.println("[negative control] unsafe GET-then-SET admitted " + admitted + " of " + REQUESTS + " (limit " + LIMIT + ")");
        assertThat(admitted).isGreaterThan(LIMIT);
    }

    @AfterAll
    static void closeClients() {
        EXTRA_CLIENTS.forEach(LettuceConnectionFactory::destroy);
    }

    private void assertConsistentState(AlgorithmType type, String redisKey) {
        Map<Object, Object> state = hash(redisKey);
        switch (type) {
            case TOKEN_BUCKET -> assertThat(Long.parseLong((String) state.get("u")))
                    .as("units left after %d takes", LIMIT).isLessThan(Duration.ofHours(24).toMillis()); // < 1 token
            case FIXED_WINDOW -> assertThat(state.get("c")).isEqualTo(Integer.toString(LIMIT));
            case SLIDING_WINDOW -> assertThat(state).containsEntry("c", Integer.toString(LIMIT)).containsEntry("p", "0");
        }
    }

    /** Runs {@code n} requests on {@code n} threads released together; returns how many were admitted. */
    private static int runConcurrently(int n, IntFunction<Boolean> request) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger admitted = new AtomicInteger();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < n; i++) {
                int index = i;
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (request.apply(index)) {
                            admitted.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).isEmpty();
        return admitted.get();
    }
}
