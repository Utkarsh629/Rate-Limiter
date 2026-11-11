package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.config.RateLimiterProperties;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cross-instance cache invalidation over Redis Pub/Sub.
 *
 * <p>After an admin change <b>commits</b>, this instance reloads its own snapshot and publishes
 * {@code <policyId>:<op>:<instanceId>} on {@code <prefix>:policy-events}. Every subscribed instance
 * then reloads from MySQL. Messages carry no policy data: MySQL stays the single source of truth,
 * and a message received twice or out of order just causes an extra reload.
 *
 * <p>Pub/Sub is fire-and-forget: an instance disconnected from Redis misses messages. The periodic
 * reload in {@link PolicyCache} bounds how stale it can get.
 */
public class PolicyInvalidation implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyInvalidation.class);

    private final PolicyCache cache;
    private final StringRedisTemplate redis;
    private final String channel;
    private final String instanceId;

    public PolicyInvalidation(PolicyCache cache, StringRedisTemplate redis, RateLimiterProperties properties) {
        this.cache = cache;
        this.redis = redis;
        this.channel = channelName(properties);
        this.instanceId = properties.instanceId();
    }

    public static String channelName(RateLimiterProperties properties) {
        return properties.keyPrefix() + ":policy-events";
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPolicyChanged(PolicyChangedEvent event) {
        cache.requestReload("local " + event.operation() + " of policy " + event.policyId());
        try {
            redis.convertAndSend(channel, event.policyId() + ":" + event.operation() + ":" + instanceId);
        } catch (RuntimeException e) {
            log.warn("Could not publish policy invalidation for policy {}; other instances will pick it up "
                    + "on their next periodic reload. Cause: {}", event.policyId(), e.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        if (body.endsWith(":" + instanceId)) {
            return; // our own change; already reloaded locally
        }
        cache.requestReload("pub/sub " + body);
    }
}
