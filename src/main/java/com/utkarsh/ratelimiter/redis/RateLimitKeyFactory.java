package com.utkarsh.ratelimiter.redis;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Builds Redis keys: {@code <prefix>:{<policyId>:<u|ip>:<subject>}:<tb|fw|sw>}.
 *
 * <ul>
 *   <li>The {@code {...}} hash tag puts all state for one (policy, subject) in one Redis Cluster
 *       slot, so every script touches exactly one slot.</li>
 *   <li>Subjects that are short and use only safe characters are kept readable. Anything else
 *       (long ids, braces, spaces, control characters) is replaced by {@code #<sha256-hex>}. Raw
 *       subjects can never contain {@code #}, so a hashed subject cannot collide with a raw one.</li>
 *   <li>The algorithm suffix means a policy that switches algorithm never reads the old format.</li>
 * </ul>
 */
public final class RateLimitKeyFactory {

    private static final Pattern SAFE_SUBJECT = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");
    private static final Pattern SAFE_PREFIX = Pattern.compile("[a-z0-9]{1,16}");

    private final String prefix;

    public RateLimitKeyFactory(String prefix) {
        if (prefix == null || !SAFE_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid key prefix: " + prefix);
        }
        this.prefix = prefix;
    }

    public String stateKey(RateLimitKey key, AlgorithmType algorithm) {
        return prefix + ":{" + key.policyId() + ':' + key.subjectType().keyCode() + ':'
                + subjectToken(key.subject()) + "}:" + algorithm.keySuffix();
    }

    static String subjectToken(String subject) {
        return SAFE_SUBJECT.matcher(subject).matches() ? subject : "#" + sha256Hex(subject);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
