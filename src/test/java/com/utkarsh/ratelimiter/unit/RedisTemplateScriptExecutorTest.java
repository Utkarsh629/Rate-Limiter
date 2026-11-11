package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.utkarsh.ratelimiter.redis.RateLimitScript;
import com.utkarsh.ratelimiter.redis.RateLimitStoreException;
import com.utkarsh.ratelimiter.redis.RedisTemplateScriptExecutor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisTemplateScriptExecutorTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final RedisTemplateScriptExecutor executor = new RedisTemplateScriptExecutor(template);

    @Test
    @SuppressWarnings("unchecked")
    void returnsIntegerReply() {
        when(template.execute(eq(RateLimitScript.FIXED_WINDOW.redisScript()), eq(List.of("k")), any(Object[].class)))
                .thenReturn((List) List.of(1L, 2L, 0L, 10L));

        assertThat(executor.execute(RateLimitScript.FIXED_WINDOW, "k", List.of("3", "1000", "")))
                .containsExactly(1L, 2L, 0L, 10L);
    }

    @Test
    void wrapsTimeoutsAndConnectionFailures() {
        when(template.execute(any(), anyList(), any(Object[].class))).thenThrow(new QueryTimeoutException("slow"));
        assertThatThrownBy(() -> executor.execute(RateLimitScript.TOKEN_BUCKET, "k", List.of()))
                .isInstanceOf(RateLimitStoreException.class).hasCauseInstanceOf(QueryTimeoutException.class);

        when(template.execute(any(), anyList(), any(Object[].class))).thenThrow(new RedisConnectionFailureException("down"));
        assertThatThrownBy(() -> executor.execute(RateLimitScript.TOKEN_BUCKET, "k", List.of()))
                .isInstanceOf(RateLimitStoreException.class).hasCauseInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsNullOrNonIntegerReplies() {
        when(template.execute(any(), anyList(), any(Object[].class))).thenReturn(null);
        assertThatThrownBy(() -> executor.execute(RateLimitScript.TOKEN_BUCKET, "k", List.of()))
                .isInstanceOf(RateLimitStoreException.class);

        when(template.execute(any(), anyList(), any(Object[].class))).thenReturn((List) Arrays.asList(1L, "x"));
        assertThatThrownBy(() -> executor.execute(RateLimitScript.TOKEN_BUCKET, "k", List.of()))
                .isInstanceOf(RateLimitStoreException.class);
    }

    @Test
    void scriptsLoadFromTheClasspath() {
        for (RateLimitScript script : RateLimitScript.values()) {
            assertThat(script.sha1()).matches("[0-9a-f]{40}");
        }
    }
}
