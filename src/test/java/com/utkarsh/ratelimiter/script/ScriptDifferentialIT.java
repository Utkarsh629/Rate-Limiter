package com.utkarsh.ratelimiter.script;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.support.ManualScriptClock;
import com.utkarsh.ratelimiter.support.RedisScriptTestSupport;
import com.utkarsh.ratelimiter.support.ReferenceModels;
import java.time.Duration;
import java.util.Random;
import java.util.function.LongFunction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs each Lua script and an independent Java model ({@link ReferenceModels}) side by side over
 * thousands of random steps (bursts, idle gaps, window jumps, clock moving backwards) and requires
 * identical results at every step. The sliding-window model finds Retry-After by brute-force search
 * rather than the closed-form formula used in Lua, so the formula itself is checked too.
 *
 * <p>Keys are PERSISTed after each step: this test checks the algorithm logic; real-time expiry is
 * covered by the per-algorithm tests.
 */
class ScriptDifferentialIT extends RedisScriptTestSupport {

    private static final int STEPS = 3_000;
    private static final long[] WINDOWS = {100, 1_000, 7_000, 60_000};

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 42, 20261110})
    void tokenBucketMatchesModel(long seed) {
        Random random = new Random(seed);
        long capacity = 1 + random.nextInt(20);
        long refill = 1 + random.nextInt(40);
        long period = WINDOWS[random.nextInt(WINDOWS.length)];
        var model = new ReferenceModels.TokenBucket(capacity, refill, period);
        compare(seed, random, RateLimitRule.tokenBucket(capacity, refill, Duration.ofMillis(period)), model::acquire);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 42, 20261110})
    void fixedWindowMatchesModel(long seed) {
        Random random = new Random(seed);
        long limit = 1 + random.nextInt(20);
        long window = WINDOWS[random.nextInt(WINDOWS.length)];
        var model = new ReferenceModels.FixedWindow(limit, window);
        compare(seed, random, RateLimitRule.fixedWindow(limit, Duration.ofMillis(window)), model::acquire);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 42, 20261110})
    void slidingWindowMatchesModel(long seed) {
        Random random = new Random(seed);
        long limit = 1 + random.nextInt(20);
        long window = WINDOWS[random.nextInt(3)]; // brute-force retry search stays fast for W <= 7 s
        var model = new ReferenceModels.SlidingWindow(limit, window);
        compare(seed, random, RateLimitRule.slidingWindow(limit, Duration.ofMillis(window)), model::acquire);
    }

    private void compare(long seed, Random random, RateLimitRule rule, LongFunction<long[]> model) {
        ManualScriptClock clock = new ManualScriptClock(T0 + random.nextInt(100_000));
        RateLimitAlgorithm algorithm = switch (rule.algorithm()) {
            case TOKEN_BUCKET -> tokenBucket(clock);
            case FIXED_WINDOW -> fixedWindow(clock);
            case SLIDING_WINDOW -> slidingWindow(clock);
        };
        RateLimitKey key = RateLimitKey.user(1, "diff-" + seed);
        String redisKey = keyFactory.stateKey(key, rule.algorithm());
        long w = rule.windowMillis();
        int allowedCount = 0;

        for (int step = 0; step < STEPS; step++) {
            int roll = random.nextInt(100);
            if (roll < 30) {
                // burst: same millisecond
            } else if (roll < 75) {
                clock.advance(1 + random.nextInt((int) Math.max(1, w / rule.limit())));
            } else if (roll < 95) {
                clock.advance(1 + random.nextInt((int) (w * 3 / 2)));
            } else {
                clock.advance(-1 - random.nextInt((int) Math.max(1, w / 2))); // clock moves back
            }

            RateLimitResult actual = algorithm.tryAcquire(key, rule);
            long[] expected = model.apply(clock.now());
            redis.persist(redisKey);

            assertThat(new long[] {actual.allowed() ? 1 : 0, actual.remaining(), actual.retryAfterMillis(), actual.resetAfterMillis()})
                    .as("%s seed=%d step=%d now=%d rule=%s", rule.algorithm(), seed, step, clock.now(), rule)
                    .containsExactly(expected);
            allowedCount += actual.allowed() ? 1 : 0;
        }
        // The random walk must exercise both outcomes, or the comparison proves little.
        assertThat(allowedCount).isBetween(1, STEPS - 1);
    }
}
