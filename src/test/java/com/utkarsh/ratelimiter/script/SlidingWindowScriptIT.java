package com.utkarsh.ratelimiter.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.algorithm.SlidingWindowAlgorithm;
import com.utkarsh.ratelimiter.redis.RedisServerClock;
import com.utkarsh.ratelimiter.support.ManualScriptClock;
import com.utkarsh.ratelimiter.support.RedisScriptTestSupport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Sliding window counter Lua script against real Redis with a deterministic clock.
 * Rule under test unless stated: 10 requests per 10 s. T0 is a window start; "window k" starts at T0
 * and "window k+1" at T0 + W.
 *
 * <p>Admission test: {@code prev × (W − e) + (cur + 1) × W ≤ limit × W}.
 */
class SlidingWindowScriptIT extends RedisScriptTestSupport {

    private static final long W = 10_000;
    private static final RateLimitRule RULE = RateLimitRule.slidingWindow(10, Duration.ofMillis(W));
    private static final RateLimitKey KEY = RateLimitKey.user(3, "carol");

    private final ManualScriptClock clock = new ManualScriptClock(T0);
    private final SlidingWindowAlgorithm sliding = slidingWindow(clock);
    private final String redisKey = keyFactory.stateKey(KEY, AlgorithmType.SLIDING_WINDOW);

    // ---------- current window only ----------

    @Test
    void withNoPreviousTrafficItCountsLikeASimpleCounter() {
        clock.set(T0 + 2_000);
        for (int expectedRemaining = 9; expectedRemaining >= 0; expectedRemaining--) {
            RateLimitResult result = sliding.tryAcquire(KEY, RULE);
            assertThat(result).isEqualTo(new RateLimitResult(true, 10, expectedRemaining, 0, 18_000));
        }

        // Full: wait until window k+1, where the 10 requests weigh 10 × (W − e)/W; one more fits
        // once that drops to 9, i.e. e = 1000 -> 8000 ms left here + 1000 ms.
        assertThat(sliding.tryAcquire(KEY, RULE)).isEqualTo(new RateLimitResult(false, 10, 0, 9_000, 18_000));
        assertThat(hash(redisKey)).isEqualTo(Map.of("ws", Long.toString(T0), "c", "10", "p", "0"));
    }

    @Test
    void retryAfterIsExactWhenTheWaitCrossesIntoTheNextWindow() {
        clock.set(T0 + 2_000);
        admit(10);
        RateLimitResult rejected = sliding.tryAcquire(KEY, RULE);

        clock.advance(rejected.retryAfterMillis() - 1);
        assertThat(sliding.tryAcquire(KEY, RULE).allowed()).isFalse();
        clock.advance(1);
        assertThat(sliding.tryAcquire(KEY, RULE).allowed()).isTrue();
    }

    // ---------- previous + current window ----------

    @Test
    void weightedCountUsesTheRemainingFractionOfThePreviousWindow() {
        clock.set(T0 + 1_000);
        admit(10);

        clock.set(T0 + W + 2_500); // 25 % into window k+1: previous weighs 10 × 0.75 = 7.5
        RateLimitResult first = sliding.tryAcquire(KEY, RULE);
        RateLimitResult second = sliding.tryAcquire(KEY, RULE);
        RateLimitResult third = sliding.tryAcquire(KEY, RULE);

        assertThat(first).isEqualTo(new RateLimitResult(true, 10, 1, 0, 17_500));   // 8.5 used
        assertThat(second).isEqualTo(new RateLimitResult(true, 10, 0, 0, 17_500));  // 9.5 used
        assertThat(third.allowed()).isFalse();                                      // 10.5 > 10
        assertThat(third.retryAfterMillis()).isEqualTo(500);                        // 9.5 + 1 fits at e = 3000
    }

    @Test
    void retryAfterIsExactWithinTheCurrentWindow() {
        clock.set(T0 + 1_000);
        admit(10);
        clock.set(T0 + W + 2_500);
        admit(2);
        RateLimitResult rejected = sliding.tryAcquire(KEY, RULE);
        assertThat(rejected.retryAfterMillis()).isEqualTo(500);

        clock.advance(499);
        assertThat(sliding.tryAcquire(KEY, RULE).allowed()).isFalse();
        clock.advance(1);
        assertThat(sliding.tryAcquire(KEY, RULE).allowed()).isTrue();
    }

    @ParameterizedTest(name = "e = {0} ms into the next window -> {1} admitted")
    @CsvSource({"0, 0", "1, 0", "999, 0", "1000, 1", "2500, 2", "5000, 5", "7500, 7", "9999, 9"})
    void previousWindowWeightDecreasesLinearly(long elapsed, int expectedAdmitted) {
        clock.set(T0 + 1_000);
        admit(10);

        clock.set(T0 + W + elapsed);
        assertThat(admitUntilRejected()).isEqualTo(expectedAdmitted); // floor(10 × e / W)
    }

    // ---------- transitions ----------

    @Test
    void windowTransitionMovesCurrentCountIntoPrevious() {
        clock.set(T0 + 1_000);
        admit(4);
        clock.set(T0 + W + 6_000);
        admit(1);

        assertThat(hash(redisKey)).isEqualTo(Map.of("ws", Long.toString(T0 + W), "c", "1", "p", "4"));
    }

    @Test
    void skippingMoreThanOneWindowForgetsEverything() {
        clock.set(T0 + 1_000);
        admit(10);
        clock.set(T0 + 2 * W); // window k+2: window k is entirely outside the sliding window

        assertThat(admitUntilRejected()).isEqualTo(10);
    }

    @Test
    void boundaryBurstIsSmoothedComparedWithFixedWindow() {
        // Same traffic that lets a fixed window admit 2 × limit in 2 ms (see FixedWindowScriptIT).
        clock.set(T0 + W - 1);
        assertThat(admitUntilRejected()).isEqualTo(10);
        clock.set(T0 + W);
        assertThat(admitUntilRejected()).isZero();
    }

    @Test
    void approximationCanExceedTheLimitInTheTrueSlidingWindowWhenTrafficIsSkewed() {
        // Documents the approximation, not a bug: all 10 previous requests arrive in the LAST ms of
        // window k, but the algorithm assumes they were spread evenly. Near the end of window k+1
        // it believes most of them have slid out and admits 9 more, so the real number of requests
        // in (now - W, now] is 19 = 1.9 × limit. Worst case approaches 2 × limit, spread over a full
        // window rather than in a burst. See docs/05-rate-limiting-algorithms.md.
        clock.set(T0 + W - 1);
        admit(10);
        clock.set(T0 + 2 * W - 2);

        assertThat(admitUntilRejected()).isEqualTo(9);
    }

    @Test
    void clockMovingBackwardsDoesNotRewindTheWindow() {
        clock.set(T0 + W + 100);
        admit(10);
        clock.set(T0 + W - 100); // window k according to this clock

        assertThat(sliding.tryAcquire(KEY, RULE).allowed()).isFalse();
    }

    // ---------- boundaries ----------

    @Test
    void limitOfOne() {
        RateLimitRule one = RateLimitRule.slidingWindow(1, Duration.ofMillis(W));
        assertThat(sliding.tryAcquire(KEY, one)).isEqualTo(new RateLimitResult(true, 1, 0, 0, 20_000));
        // Next window: previous = 1, fits again only once its weight is 0, i.e. at the end of window k+1.
        assertThat(sliding.tryAcquire(KEY, one)).isEqualTo(new RateLimitResult(false, 1, 0, 20_000, 20_000));
    }

    @Test
    void rejectedRequestDoesNotModifyStateOrExtendTtl() {
        admit(10);
        Map<Object, Object> before = hash(redisKey);
        long ttlBefore = pttl(redisKey);

        clock.advance(300);
        assertThat(sliding.tryAcquire(KEY, RULE).allowed()).isFalse();

        assertThat(hash(redisKey)).isEqualTo(before);
        assertThat(pttl(redisKey)).isLessThanOrEqualTo(ttlBefore);
    }

    // ---------- expiration ----------

    @Test
    void ttlCoversTheNextWindowPlusGrace() {
        clock.set(T0 + 2_000);
        sliding.tryAcquire(KEY, RULE);

        // 2W − e + 1 s grace = 19 000 ms, minus real time elapsed since PEXPIRE
        assertThat(pttl(redisKey)).isBetween(18_000L, 19_000L);
    }

    @Test
    void keyExpiresInRealTimeUsingRedisServerTime() {
        SlidingWindowAlgorithm production = slidingWindow(RedisServerClock.INSTANCE);
        RateLimitRule shortWindow = RateLimitRule.slidingWindow(3, Duration.ofMillis(200));

        assertThat(production.tryAcquire(KEY, shortWindow).allowed()).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> !redis.hasKey(redisKey));
    }

    @Test
    void quotaRecoversUsingRedisServerTime() {
        SlidingWindowAlgorithm production = slidingWindow(RedisServerClock.INSTANCE);
        RateLimitRule shortWindow = RateLimitRule.slidingWindow(2, Duration.ofMillis(400));

        while (production.tryAcquire(KEY, shortWindow).allowed()) {
            // drain
        }
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(2))
                .until(() -> production.tryAcquire(KEY, shortWindow).allowed());
    }

    private void admit(int n) {
        for (int i = 0; i < n; i++) {
            assertThat(sliding.tryAcquire(KEY, RULE).allowed()).isTrue();
        }
    }

    private int admitUntilRejected() {
        int allowed = 0;
        while (sliding.tryAcquire(KEY, RULE).allowed()) {
            allowed++;
        }
        return allowed;
    }
}
