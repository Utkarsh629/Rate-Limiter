package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.redis.RedisFailureClassifier;
import com.utkarsh.ratelimiter.redis.RedisFailureClassifier.Type;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import java.io.IOException;
import java.net.ConnectException;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.PoolException;

/** Only Redis infrastructure failures may count toward opening the breaker (design §4.2). */
class RedisFailureClassifierTest {

    /** As the executor delivers them: wrapped in RateLimitStoreException. */
    private static Optional<Type> classify(Throwable cause) {
        return RedisFailureClassifier.classify(new RateLimitStoreException("Redis script failed", cause));
    }

    @Test
    void timeoutsAreInfrastructure() {
        assertThat(classify(new QueryTimeoutException("Redis command timed out", new RedisCommandTimeoutException("50 ms"))))
                .contains(Type.TIMEOUT);
        assertThat(classify(new RedisCommandTimeoutException("raw"))).contains(Type.TIMEOUT);
    }

    @Test
    void connectionFailuresAreInfrastructure() {
        assertThat(classify(new RedisConnectionFailureException("refused", new RedisConnectionException("x", new ConnectException()))))
                .contains(Type.CONNECTION);
        assertThat(classify(new RedisSystemException("io", new IOException("Connection reset by peer")))).contains(Type.CONNECTION);
        assertThat(classify(new DataAccessResourceFailureException("resource"))).contains(Type.CONNECTION);
    }

    @Test
    void poolExhaustionIsInfrastructureEvenWhenWrapped() {
        PoolException pool = new PoolException("Could not get a resource from the pool", new NoSuchElementException("Timeout waiting for idle object"));
        assertThat(classify(pool)).contains(Type.POOL);
        assertThat(classify(new RedisSystemException("Unknown redis exception", pool))).contains(Type.POOL);
        assertThat(classify(new RedisConnectionFailureException("wrapped", pool))).contains(Type.POOL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "OOM command not allowed when used memory > 'maxmemory'.",
            "BUSY Redis is busy running a script. You can only call SCRIPT KILL or SHUTDOWN NOSAVE.",
            "LOADING Redis is loading the dataset in memory",
            "MASTERDOWN Link with MASTER is down and replica-serve-stale-data is set to 'no'.",
            "READONLY You can't write against a read only replica.",
            "CLUSTERDOWN The cluster is down",
            "TRYAGAIN Multiple keys request during rehashing of slot"})
    void serverUnavailableRepliesAreInfrastructure(String reply) {
        assertThat(classify(new RedisSystemException("Error in execution", new RedisCommandExecutionException(reply))))
                .contains(Type.SERVER);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ERR Error running script (call to f_abc): @user_script:12: user_script:12: attempt to compare nil with number",
            "WRONGTYPE Operation against a key holding the wrong kind of value",
            "ERR wrong number of arguments for 'evalsha' command",
            "NOSCRIPT No matching script. Please use EVAL."})
    void programmingAndContractErrorsAreIgnored(String reply) {
        assertThat(classify(new RedisSystemException("Error in execution", new RedisCommandExecutionException(reply)))).isEmpty();
    }

    @Test
    void malformedRepliesAndUnknownErrorsAreIgnored() {
        assertThat(RedisFailureClassifier.classify(new RateLimitStoreException("returned non-integer element"))).isEmpty();
        assertThat(classify(new InvalidDataAccessApiUsageException("bad usage"))).isEmpty();
        assertThat(RedisFailureClassifier.classify(new IllegalStateException("bug"))).isEmpty();
        assertThat(RedisFailureClassifier.classify(new NullPointerException())).isEmpty();
    }
}
