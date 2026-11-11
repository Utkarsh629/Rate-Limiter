package com.utkarsh.ratelimiter.script;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.metrics.RateLimitOutcome;
import com.utkarsh.ratelimiter.stats.PolicyStatsCollector;
import com.utkarsh.ratelimiter.stats.RedisStatsSink;
import com.utkarsh.ratelimiter.stats.StatsBatch;
import com.utkarsh.ratelimiter.stats.StatsKeys;
import com.utkarsh.ratelimiter.support.RedisScriptTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** stats_apply.lua against real Redis: at-most-once per (boot, seq), TTL, index, and a real ambiguous failure. */
class StatsApplyScriptIT extends RedisScriptTestSupport {

    private final StatsKeys keys = new StatsKeys("rl");
    private final RedisStatsSink sink = new RedisStatsSink(redis, keys, "app-1", Duration.ofHours(24));

    private static StatsBatch batch(String boot, long seq, long... deltas) {
        return new StatsBatch(boot, seq, Map.of(7L, deltas));
    }

    @Test
    void appliesABatchOnceAndSkipsItsRetry() {
        sink.apply(batch("boot-a", 1, 5, 2, 0, 1));
        sink.apply(batch("boot-a", 1, 5, 2, 0, 1)); // retry after an ambiguous failure
        sink.apply(batch("boot-a", 2, 1, 0, 0, 0));

        Map<Object, Object> shard = hash(keys.shard(7, "app-1"));
        assertThat(shard).containsEntry("allowed", "6").containsEntry("rejected", "2")
                .containsEntry("fail_open", "0").containsEntry("fail_closed", "1").containsEntry("seq", "2");
        assertThat(redis.opsForSet().members(keys.index(7))).containsExactly("app-1");
        assertThat(pttl(keys.shard(7, "app-1"))).isBetween(Duration.ofHours(23).toMillis(), Duration.ofHours(24).toMillis());
        assertThat(pttl(keys.index(7))).isPositive();
    }

    @Test
    void aRestartedInstanceStartsANewSequence() {
        sink.apply(batch("boot-a", 9, 3, 0, 0, 0));
        sink.apply(batch("boot-b", 1, 4, 0, 0, 0)); // new process: seq restarts at 1
        assertThat(hash(keys.shard(7, "app-1"))).containsEntry("allowed", "7").containsEntry("boot", "boot-b");
    }

    @Test
    void redisPausedDuringAFlushNeitherLosesNorDoubleCounts() throws Exception {
        // A client with the production 50 ms command timeout: while Redis is paused the flush times out,
        // but the command is already in the socket and runs when Redis resumes (the ambiguous case).
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50)).build());
        factory.afterPropertiesSet();
        factory.start();
        try {
            StringRedisTemplate shortTimeout = new StringRedisTemplate(factory);
            PolicyStatsCollector collector = new PolicyStatsCollector(
                    new RedisStatsSink(shortTimeout, keys, "app-2", Duration.ofHours(24)), Duration.ofSeconds(5), new SimpleMeterRegistry());
            for (int i = 0; i < 5; i++) {
                collector.record(7, RateLimitOutcome.ALLOWED);
            }
            assertThat(collector.flush()).isTrue(); // warm the connection

            for (int i = 0; i < 3; i++) {
                collector.record(7, RateLimitOutcome.ALLOWED);
            }
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                assertThat(collector.flush()).as("times out while paused").isFalse();
                for (int i = 0; i < 4; i++) {
                    collector.record(7, RateLimitOutcome.FAIL_OPEN);
                }
                assertThat(collector.flush()).isFalse();
            } finally {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }
            Thread.sleep(300); // the buffered command(s) execute now
            while (!collector.flush()) {
                Thread.sleep(50);
            }
            collector.flush();

            assertThat(hash(keys.shard(7, "app-2"))).containsEntry("allowed", "8").containsEntry("fail_open", "4");
        } finally {
            factory.destroy();
        }
    }
}
