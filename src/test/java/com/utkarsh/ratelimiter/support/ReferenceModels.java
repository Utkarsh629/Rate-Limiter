package com.utkarsh.ratelimiter.support;

/**
 * Plain-Java models of the three algorithms with exact {@code long} arithmetic, written from the
 * formulas in docs/05 (not by translating the Lua line by line). Used only to cross-check the Lua
 * scripts on random inputs. Each {@code acquire} returns {@code {allowed, remaining, retryAfter, resetAfter}}.
 */
public final class ReferenceModels {

    private ReferenceModels() {
    }

    static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }

    /** Token bucket with tokens scaled by the period: one token = period units, refill = units/ms. */
    public static final class TokenBucket {
        private final long capacity;
        private final long refill;
        private final long period;
        private Long units;
        private long ts;

        public TokenBucket(long capacity, long refill, long period) {
            this.capacity = capacity;
            this.refill = refill;
            this.period = period;
        }

        public long[] acquire(long now) {
            long full = capacity * period;
            long u = units == null ? full : units;
            long t = units == null ? now : ts;
            if (now > t) {
                u = Math.min(full, u + Math.min(now - t, ceilDiv(full - u, refill)) * refill);
                t = now;
            }
            boolean allowed = u >= period;
            long retry = 0;
            if (allowed) {
                u -= period;
                units = u;
                ts = t;
            } else {
                retry = ceilDiv(period - u, refill);
            }
            return new long[] {allowed ? 1 : 0, u / period, retry, ceilDiv(full - u, refill)};
        }
    }

    /** Fixed window: count per epoch-aligned window; never moves back to an earlier window. */
    public static final class FixedWindow {
        private final long limit;
        private final long window;
        private Long index;
        private long count;

        public FixedWindow(long limit, long window) {
            this.limit = limit;
            this.window = window;
        }

        public long[] acquire(long now) {
            long current = Math.floorDiv(now, window);
            long c = 0;
            if (index != null && index >= current) {
                current = index;
                c = count;
            }
            long reset = (current + 1) * window - now;
            if (c < limit) {
                index = current;
                count = c + 1;
                return new long[] {1, limit - count, 0, reset};
            }
            return new long[] {0, 0, reset, reset};
        }
    }

    /** Sliding window counter: estimate = prev × (W − e)/W + cur, admitted while estimate + 1 ≤ limit. */
    public static final class SlidingWindow {
        private final long limit;
        private final long w;
        private Long start;
        private long cur;
        private long prev;

        public SlidingWindow(long limit, long window) {
            this.limit = limit;
            this.w = window;
        }

        public long[] acquire(long now) {
            long ws = Math.floorDiv(now, w) * w;
            long c = 0;
            long p = 0;
            if (start != null) {
                if (start == ws) {
                    c = cur;
                    p = prev;
                } else if (start == ws - w) {
                    p = cur;
                } else if (start > ws) {
                    ws = start;
                    c = cur;
                    p = prev;
                }
            }
            long e = Math.max(0, now - ws);
            long used = p * (w - e) + c * w;
            long retry = 0;
            boolean allowed = used + w <= limit * w;
            if (allowed) {
                c++;
                used += w;
                start = ws;
                cur = c;
                prev = p;
            } else {
                retry = earliestRetry(c, p, e);
            }
            long reset = c > 0 ? 2 * w - e : p > 0 ? w - e : 0;
            return new long[] {allowed ? 1 : 0, Math.max(0, Math.floorDiv(limit * w - used, w)), retry, reset};
        }

        /** Brute-force search for the first t >= 1 at which one more request would be admitted. */
        private long earliestRetry(long c, long p, long e) {
            for (long t = 1; ; t++) {
                long e1 = e + t;
                long used = e1 < w ? p * (w - e1) + c * w : c * (w - (e1 - w));
                if (e1 >= 2 * w) {
                    used = 0;
                }
                if (used + w <= limit * w) {
                    return t;
                }
            }
        }
    }
}
