package com.utkarsh.ratelimiter.redis;

/**
 * Supplies the time argument passed to every rate-limit script.
 *
 * <p>In production this is always {@link RedisServerClock}: the argument is empty and the script
 * reads {@code redis.call('TIME')}, so every application instance uses the same clock regardless of
 * its own system time. A fixed-time implementation exists only in test sources, to make
 * time-dependent behaviour (refill, window roll-over, boundary bursts) deterministic. No
 * configuration property or HTTP input can select it.
 */
public interface ScriptClock {

    /** Empty string = "use Redis TIME"; otherwise epoch milliseconds as a decimal string. */
    String timeArgument();
}
