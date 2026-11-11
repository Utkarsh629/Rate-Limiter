package com.utkarsh.ratelimiter.redis;

/** The production clock: scripts read Redis server time. */
public final class RedisServerClock implements ScriptClock {

    public static final RedisServerClock INSTANCE = new RedisServerClock();

    private RedisServerClock() {
    }

    @Override
    public String timeArgument() {
        return "";
    }
}
