package com.utkarsh.ratelimiter.redis;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.PoolException;

/**
 * Decides whether an exception from a Redis script call is an <b>infrastructure</b> failure, which
 * counts toward opening the circuit breaker, or anything else, which is ignored by it.
 *
 * <p>An allow-list: only the causes below count. Unknown exception types, Lua runtime errors
 * ({@code ERR Error running script}), {@code WRONGTYPE}, malformed replies and other programming
 * errors are ignored, so a bug can never be mistaken for an outage and hidden behind a failure mode.
 */
public final class RedisFailureClassifier {

    /** Infrastructure failure types; also the {@code type} tag of {@code ratelimiter_redis_errors_total}. */
    public enum Type {
        TIMEOUT, CONNECTION, POOL, SERVER;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Redis error replies meaning "the server cannot serve right now", not "your request is wrong". */
    private static final Set<String> SERVER_UNAVAILABLE_PREFIXES =
            Set.of("OOM", "BUSY", "LOADING", "MASTERDOWN", "READONLY", "CLUSTERDOWN", "TRYAGAIN");

    private RedisFailureClassifier() {
    }

    /** @return the infrastructure failure type, or empty if the error must not count toward opening */
    public static Optional<Type> classify(Throwable error) {
        // Pool exhaustion first: it can arrive wrapped in a connection-type exception.
        for (Throwable t = error; t != null; t = next(t)) {
            if (t instanceof PoolException) {
                return Optional.of(Type.POOL);
            }
        }
        for (Throwable t = error; t != null; t = next(t)) {
            if (t instanceof QueryTimeoutException || t instanceof TimeoutException
                    || t.getClass().getName().equals("io.lettuce.core.RedisCommandTimeoutException")) {
                return Optional.of(Type.TIMEOUT);
            }
            if (isServerUnavailableReply(t)) {
                return Optional.of(Type.SERVER);
            }
            if (t instanceof RedisConnectionFailureException || t instanceof IOException
                    || t.getClass().getName().equals("io.lettuce.core.RedisConnectionException")) {
                return Optional.of(Type.CONNECTION);
            }
            if (t instanceof DataAccessResourceFailureException) {
                return Optional.of(Type.CONNECTION);
            }
        }
        return Optional.empty();
    }

    private static boolean isServerUnavailableReply(Throwable t) {
        if (!t.getClass().getName().equals("io.lettuce.core.RedisCommandExecutionException")) {
            return false;
        }
        String message = t.getMessage();
        if (message == null) {
            return false;
        }
        int space = message.indexOf(' ');
        String prefix = space < 0 ? message : message.substring(0, space);
        return SERVER_UNAVAILABLE_PREFIXES.contains(prefix);
    }

    private static Throwable next(Throwable t) {
        return t.getCause() == t ? null : t.getCause();
    }
}
