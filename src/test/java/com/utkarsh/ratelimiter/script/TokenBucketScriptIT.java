package com.utkarsh.ratelimiter.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.utkarsh.ratelimiter.redis.RedisServerClock;
import com.utkarsh.ratelimiter.support.ManualScriptClock;
import com.utkarsh.ratelimiter.support.RedisScriptTestSupport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Token bucket Lua script against real Redis with a deterministic clock.
 * Rule under test unless stated: capacity 10, refill 10 tokens / 10 s = 1 token per 1000 ms.
 */
class TokenBucketScriptIT extends RedisScriptTestSupport {

    private static final RateLimitRule RULE = RateLimitRule.tokenBucket(10, 10, Duration.ofSeconds(10));
    private static final RateLimitKey KEY = RateLimitKey.user(1, "alice");

    private final ManualScriptClock clock = new ManualScriptClock(T0);
    private final TokenBucketAlgorithm bucket = tokenBucket(clock);
    private final String redisKey = keyFactory.stateKey(KEY, AlgorithmType.TOKEN_BUCKET);

    // ---------- normal operation ----------

    @Test
    void missingKeyIsAFullBucketAndIsInitialisedOnFirstRequest() {
        assertThat(redis.hasKey(redisKey)).isFalse();

        RateLimitResult first = bucket.tryAcquire(KEY, RULE);

        assertThat(first).isEqualTo(new RateLimitResult(true, 10, 9, 0, 1_000));
        // units = 9 tokens × 10 000 units/token; ts = the request time
        assertThat(hash(redisKey)).isEqualTo(Map.of("u", "90000", "ts", Long.toString(T0)));
    }

    @Test
    void eachRequestConsumesOneToken() {
        for (int expectedRemaining = 9; expectedRemaining >= 0; expectedRemaining--) {
            RateLimitResult result = bucket.tryAcquire(KEY, RULE);
            assertThat(result.allowed()).isTrue();
            assertThat(result.remaining()).isEqualTo(expectedRemaining);
            assertThat(result.resetAfterMillis()).isEqualTo((10 - expectedRemaining) * 1_000L);
        }
    }

    @Test
    void emptyBucketRejectsWithRetryAfterOneRefillInterval() {
        drain();

        RateLimitResult rejected = bucket.tryAcquire(KEY, RULE);

        assertThat(rejected).isEqualTo(new RateLimitResult(false, 10, 0, 1_000, 10_000));
    }

    // ---------- refill ----------

    @Test
    void partialRefillAddsTokensProportionalToElapsedTime() {
        drain();
        clock.advance(3_500); // 3.5 tokens

        assertThat(acquireUntilRejected()).isEqualTo(3);
        RateLimitResult rejected = bucket.tryAcquire(KEY, RULE);
        assertThat(rejected.retryAfterMillis()).isEqualTo(500); // the remaining half token
    }

    @Test
    void completeRefillRestoresCapacityButNeverExceedsIt() {
        drain();
        clock.advance(10_000);
        assertThat(acquireUntilRejected()).isEqualTo(10);

        clock.advance(Duration.ofDays(1).toMillis()); // far more than needed
        assertThat(acquireUntilRejected()).isEqualTo(10);
    }

    @Test
    void retryAfterIsExactToTheMillisecond() {
        drain();
        RateLimitResult rejected = bucket.tryAcquire(KEY, RULE);

        clock.advance(rejected.retryAfterMillis() - 1);
        assertThat(bucket.tryAcquire(KEY, RULE).allowed()).isFalse();

        clock.advance(1);
        assertThat(bucket.tryAcquire(KEY, RULE).allowed()).isTrue();
    }

    @Test
    void nonTerminatingRefillRateIsExact() {
        // 20 tokens per minute = one token every 3000 ms; 20/60000 has no exact decimal form.
        RateLimitRule perMinute = RateLimitRule.tokenBucket(20, 20, Duration.ofMinutes(1));
        for (int i = 0; i < 20; i++) {
            bucket.tryAcquire(KEY, perMinute);
        }
        for (int token = 0; token < 20; token++) {
            RateLimitResult rejected = bucket.tryAcquire(KEY, perMinute);
            assertThat(rejected.retryAfterMillis()).isEqualTo(3_000);
            clock.advance(2_999);
            assertThat(bucket.tryAcquire(KEY, perMinute).allowed()).isFalse();
            clock.advance(1);
            assertThat(bucket.tryAcquire(KEY, perMinute).allowed()).isTrue();
        }
    }

    @Test
    void slowRefillAccumulatesAcrossCalls() {
        // capacity 5, 1 token per 4000 ms; check at 1 s steps that the 4th second yields one token
        RateLimitRule slow = RateLimitRule.tokenBucket(5, 1, Duration.ofSeconds(4));
        for (int i = 0; i < 5; i++) {
            bucket.tryAcquire(KEY, slow);
        }
        for (int second = 1; second <= 3; second++) {
            clock.advance(1_000);
            assertThat(bucket.tryAcquire(KEY, slow).allowed()).as("after %d s", second).isFalse();
        }
        clock.advance(1_000);
        assertThat(bucket.tryAcquire(KEY, slow).allowed()).isTrue();
    }

    // ---------- boundaries ----------

    @Test
    void capacityOfOneAllowsExactlyOneRequestPerRefillPeriod() {
        RateLimitRule single = RateLimitRule.tokenBucket(1, 1, Duration.ofSeconds(2));
        assertThat(bucket.tryAcquire(KEY, single)).isEqualTo(new RateLimitResult(true, 1, 0, 0, 2_000));
        assertThat(bucket.tryAcquire(KEY, single)).isEqualTo(new RateLimitResult(false, 1, 0, 2_000, 2_000));
        clock.advance(2_000);
        assertThat(bucket.tryAcquire(KEY, single).allowed()).isTrue();
    }

    @Test
    void rejectedRequestDoesNotModifyState() {
        drain();
        Map<Object, Object> before = hash(redisKey);
        long ttlBefore = pttl(redisKey);

        clock.advance(400);
        assertThat(bucket.tryAcquire(KEY, RULE).allowed()).isFalse();

        assertThat(hash(redisKey)).isEqualTo(before);
        assertThat(pttl(redisKey)).isLessThanOrEqualTo(ttlBefore); // TTL not extended by rejections
    }

    @Test
    void clockMovingBackwardsNeverCreatesTokens() {
        drain();
        clock.advance(-5_000);
        assertThat(bucket.tryAcquire(KEY, RULE).allowed()).isFalse();

        // Back to the original time: still nothing, because no real time has passed since T0.
        clock.set(T0);
        assertThat(bucket.tryAcquire(KEY, RULE).allowed()).isFalse();
        clock.set(T0 + 1_000);
        assertThat(bucket.tryAcquire(KEY, RULE).allowed()).isTrue();
    }

    @Test
    void differentSubjectsAndPoliciesHaveIndependentBuckets() {
        drain();
        assertThat(bucket.tryAcquire(RateLimitKey.user(1, "bob"), RULE).allowed()).isTrue();
        assertThat(bucket.tryAcquire(RateLimitKey.user(2, "alice"), RULE).allowed()).isTrue();
        assertThat(bucket.tryAcquire(RateLimitKey.ip(1, "alice"), RULE).allowed()).isTrue();
    }

    // ---------- expiration ----------

    @Test
    void ttlIsTimeUntilTheBucketIsFullAgainPlusGrace() {
        bucket.tryAcquire(KEY, RULE);
        bucket.tryAcquire(KEY, RULE);
        RateLimitResult third = bucket.tryAcquire(KEY, RULE);

        assertThat(third.resetAfterMillis()).isEqualTo(3_000);
        // reset + 1 s grace, minus the real milliseconds elapsed since PEXPIRE
        assertThat(pttl(redisKey)).isBetween(3_000L, 4_000L);
    }

    @Test
    void keyExpiresInRealTimeOnceRefilledUsingRedisServerTime() {
        TokenBucketAlgorithm production = tokenBucket(RedisServerClock.INSTANCE);
        RateLimitRule fast = RateLimitRule.tokenBucket(4, 4, Duration.ofMillis(400)); // full again 100 ms after one request

        RateLimitResult result = production.tryAcquire(KEY, fast);
        assertThat(result.allowed()).isTrue();
        assertThat(redis.hasKey(redisKey)).isTrue();

        await().atMost(Duration.ofSeconds(2)).until(() -> !redis.hasKey(redisKey));
        // Missing key behaves as full.
        assertThat(production.tryAcquire(KEY, fast).remaining()).isEqualTo(3);
    }

    @Test
    void refillUsingRedisServerTime() {
        TokenBucketAlgorithm production = tokenBucket(RedisServerClock.INSTANCE);
        RateLimitRule rule = RateLimitRule.tokenBucket(2, 2, Duration.ofMillis(600)); // 1 token / 300 ms

        assertThat(production.tryAcquire(KEY, rule).allowed()).isTrue();
        assertThat(production.tryAcquire(KEY, rule).allowed()).isTrue();
        RateLimitResult rejected = production.tryAcquire(KEY, rule);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterMillis()).isBetween(1L, 300L);

        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(2))
                .until(() -> production.tryAcquire(KEY, rule).allowed());
    }

    private void drain() {
        for (int i = 0; i < 10; i++) {
            assertThat(bucket.tryAcquire(KEY, RULE).allowed()).isTrue();
        }
    }

    private int acquireUntilRejected() {
        int allowed = 0;
        while (bucket.tryAcquire(KEY, RULE).allowed()) {
            allowed++;
        }
        return allowed;
    }
}
