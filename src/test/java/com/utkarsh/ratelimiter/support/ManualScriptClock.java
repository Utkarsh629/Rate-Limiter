package com.utkarsh.ratelimiter.support;

import com.utkarsh.ratelimiter.redis.ScriptClock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only clock: scripts use this fixed time instead of Redis TIME, so refill, window roll-over
 * and retry timing can be asserted to the millisecond. Exists only in test sources by design.
 */
public final class ManualScriptClock implements ScriptClock {

    private final AtomicLong nowMillis;

    public ManualScriptClock(long startMillis) {
        this.nowMillis = new AtomicLong(startMillis);
    }

    public long now() {
        return nowMillis.get();
    }

    public void set(long millis) {
        nowMillis.set(millis);
    }

    public void advance(long millis) {
        nowMillis.addAndGet(millis);
    }

    @Override
    public String timeArgument() {
        return Long.toString(nowMillis.get());
    }
}
