package com.utkarsh.ratelimiter.stats;

import com.utkarsh.ratelimiter.redis.ScriptText;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Writes batches to Redis with {@code scripts/stats_apply.lua}: one atomic, idempotent call per policy shard.
 *
 * <p>Sent with plain {@code EVAL} (not {@code EVALSHA}). At one call per policy per flush interval the extra
 * bytes are irrelevant, and it keeps {@code EVALSHA} exclusive to rate-limit decisions, so Redis's own
 * {@code INFO commandstats} remains an independent count of decisions that reached Redis.
 */
public class RedisStatsSink implements StatsSink {

    static final byte[] APPLY = ScriptText.load("scripts/stats_apply.lua").getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redis;
    private final StatsKeys keys;
    private final String instanceId;
    private final Duration ttl;

    public RedisStatsSink(StringRedisTemplate redis, StatsKeys keys, String instanceId, Duration ttl) {
        this.redis = redis;
        this.keys = keys;
        this.instanceId = instanceId;
        this.ttl = ttl;
    }

    @Override
    public void apply(StatsBatch batch) {
        for (Map.Entry<Long, long[]> entry : batch.deltas().entrySet()) {
            long[] d = entry.getValue();
            byte[][] keysAndArgs = bytes(
                    keys.shard(entry.getKey(), instanceId), keys.index(entry.getKey()),
                    instanceId, batch.bootId(), Long.toString(batch.seq()),
                    Long.toString(d[0]), Long.toString(d[1]), Long.toString(d[2]), Long.toString(d[3]),
                    Long.toString(ttl.toMillis()));
            redis.execute((RedisCallback<Long>) connection ->
                    connection.scriptingCommands().eval(APPLY, ReturnType.INTEGER, 2, keysAndArgs));
        }
    }

    private static byte[][] bytes(String... values) {
        byte[][] out = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }
}
