package com.utkarsh.ratelimiter.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.FixedWindowAlgorithm;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.redis.RedisServerClock;
import com.utkarsh.ratelimiter.support.ManualScriptClock;
import com.utkarsh.ratelimiter.support.RedisScriptTestSupport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fixed window Lua script against real Redis with a deterministic clock.
 * Rule under test unless stated: 5 requests per 60 s window. T0 is a window start.
 */
class FixedWindowScriptIT extends RedisScriptTestSupport {

    private static final long W = 60_000;
    private static final RateLimitRule RULE = RateLimitRule.fixedWindow(5, Duration.ofMillis(W));
    private static final RateLimitKey KEY = RateLimitKey.ip(7, "203.0.113.7");

    private final ManualScriptClock clock = new ManualScriptClock(T0 + 15_000); // 15 s into a window
    private final FixedWindowAlgorithm window = fixedWindow(clock);
    private final String redisKey = keyFactory.stateKey(KEY, AlgorithmType.FIXED_WINDOW);

    // ---------- normal operation ----------

    @Test
    void initialRequestOpensTheWindow() {
        RateLimitResult first = window.tryAcquire(KEY, RULE);

        assertThat(first).isEqualTo(new RateLimitResult(true, 5, 4, 0, 45_000));
        assertThat(hash(redisKey)).isEqualTo(Map.of("w", Long.toString(T0 / W), "c", "1"));
    }

    @Test
    void counterIncrementsWithinTheWindow() {
        for (int n = 1; n <= 5; n++) {
            clock.advance(1_000);
            RateLimitResult result = window.tryAcquire(KEY, RULE);
            assertThat(result.allowed()).isTrue();
            assertThat(result.remaining()).isEqualTo(5 - n);
            assertThat(hash(redisKey).get("c")).isEqualTo(Integer.toString(n));
        }
    }

    @Test
    void limitReachedRejectsUntilTheWindowEnds() {
        admit(5);

        RateLimitResult rejected = window.tryAcquire(KEY, RULE);

        assertThat(rejected).isEqualTo(new RateLimitResult(false, 5, 0, 45_000, 45_000));
        assertThat(hash(redisKey).get("c")).isEqualTo("5"); // rejection did not increment
    }

    @Test
    void retryAfterIsExactToTheMillisecond() {
        admit(5);
        RateLimitResult rejected = window.tryAcquire(KEY, RULE);

        clock.advance(rejected.retryAfterMillis() - 1); // last millisecond of the window
        assertThat(window.tryAcquire(KEY, RULE).allowed()).isFalse();

        clock.advance(1); // first millisecond of the next window
        RateLimitResult next = window.tryAcquire(KEY, RULE);
        assertThat(next).isEqualTo(new RateLimitResult(true, 5, 4, 0, W));
    }

    // ---------- window expiration / transitions ----------

    @Test
    void nextWindowStartsWithAFreshCount() {
        admit(5);
        clock.set(T0 + W + 30_000);

        assertThat(admitUntilRejected()).isEqualTo(5);
        assertThat(hash(redisKey).get("w")).isEqualTo(Long.toString(T0 / W + 1));
    }

    @Test
    void skippingSeveralWindowsAlsoStartsFresh() {
        admit(5);
        clock.set(T0 + 10 * W + 1);

        assertThat(admitUntilRejected()).isEqualTo(5);
    }

    @Test
    void keyNeverExpiresBeforeItsWindowEnds() {
        // Regression: with TTL = exact time left, a request in the last millisecond set a 1 ms TTL,
        // the key vanished before the window ended, and the count restarted (103 admitted, not 5).
        clock.set(T0 + W - 1);
        assertThat(admitUntilRejected()).isEqualTo(5);
        assertThat(pttl(redisKey)).isGreaterThan(900L);
    }

    @Test
    void boundaryBurstAdmitsTwiceTheLimitWithinTwoMilliseconds() {
        // The documented weakness of fixed windows: 5 at the last ms of one window, 5 at the first
        // ms of the next -> 10 requests admitted in 2 ms, although the limit is 5 per minute.
        clock.set(T0 + W - 1);
        int lastMs = admitUntilRejected();
        clock.set(T0 + W);
        int firstMs = admitUntilRejected();

        assertThat(lastMs + firstMs).isEqualTo(10);
    }

    @Test
    void clockMovingBackAcrossABoundaryDoesNotReopenTheEarlierWindow() {
        clock.set(T0 + W + 10);
        admit(5);
        clock.set(T0 + W - 10); // previous window, according to this clock

        assertThat(window.tryAcquire(KEY, RULE).allowed()).isFalse();
    }

    // ---------- boundaries ----------

    @Test
    void limitOfOne() {
        RateLimitRule one = RateLimitRule.fixedWindow(1, Duration.ofSeconds(10));
        clock.set(T0);
        assertThat(window.tryAcquire(KEY, one)).isEqualTo(new RateLimitResult(true, 1, 0, 0, 10_000));
        assertThat(window.tryAcquire(KEY, one)).isEqualTo(new RateLimitResult(false, 1, 0, 10_000, 10_000));
    }

    @Test
    void requestAtExactWindowStartHasFullWindowReset() {
        clock.set(T0);
        assertThat(window.tryAcquire(KEY, RULE).resetAfterMillis()).isEqualTo(W);
    }

    @Test
    void rejectedRequestDoesNotModifyStateOrExtendTtl() {
        admit(5);
        Map<Object, Object> before = hash(redisKey);
        long ttlBefore = pttl(redisKey);

        window.tryAcquire(KEY, RULE);

        assertThat(hash(redisKey)).isEqualTo(before);
        assertThat(pttl(redisKey)).isLessThanOrEqualTo(ttlBefore);
    }

    // ---------- expiration ----------

    @Test
    void ttlIsTheEndOfTheWindowPlusGraceWhenItOpens() {
        window.tryAcquire(KEY, RULE); // 45 s before the window ends (+1 s grace)
        assertThat(pttl(redisKey)).isBetween(45_000L, 46_000L);

        clock.advance(10_000);
        window.tryAcquire(KEY, RULE); // same window: TTL not pushed back
        assertThat(pttl(redisKey)).isBetween(45_000L, 46_000L);
    }

    @Test
    void keyExpiresInRealTimeAtTheEndOfTheWindowUsingRedisServerTime() {
        FixedWindowAlgorithm production = fixedWindow(RedisServerClock.INSTANCE);
        RateLimitRule shortWindow = RateLimitRule.fixedWindow(3, Duration.ofMillis(300));

        assertThat(production.tryAcquire(KEY, shortWindow).allowed()).isTrue();
        assertThat(redis.hasKey(redisKey)).isTrue();

        await().atMost(Duration.ofSeconds(2)).until(() -> !redis.hasKey(redisKey));
    }

    @Test
    void windowRollsOverUsingRedisServerTime() {
        FixedWindowAlgorithm production = fixedWindow(RedisServerClock.INSTANCE);
        RateLimitRule shortWindow = RateLimitRule.fixedWindow(2, Duration.ofMillis(500));

        // Exhaust whatever window we are in, then wait for the next one.
        while (production.tryAcquire(KEY, shortWindow).allowed()) {
            // drain
        }
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(2))
                .until(() -> production.tryAcquire(KEY, shortWindow).allowed());
    }

    private void admit(int n) {
        for (int i = 0; i < n; i++) {
            assertThat(window.tryAcquire(KEY, RULE).allowed()).isTrue();
        }
    }

    private int admitUntilRejected() {
        int allowed = 0;
        while (window.tryAcquire(KEY, RULE).allowed()) {
            allowed++;
        }
        return allowed;
    }
}
