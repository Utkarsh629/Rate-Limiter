package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.algorithm.AlgorithmType;
import com.utkarsh.ratelimiter.algorithm.RateLimitRule;
import com.utkarsh.ratelimiter.identity.Tier;
import com.utkarsh.ratelimiter.resilience.FailureMode;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * An enabled policy compiled for the request path: the endpoint pattern is parsed once and the
 * limits are already a validated {@link RateLimitRule}. Immutable and safe to share across threads.
 *
 * @param httpMethod null = any method
 * @param tier       null = any tier
 * @param failureMode null = the global default
 */
public record ResolvedPolicy(
        long id,
        String name,
        HttpMethod httpMethod,
        PathPattern pattern,
        Tier tier,
        KeyBy keyBy,
        RateLimitRule rule,
        FailureMode failureMode,
        int priority,
        int version) {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    public static final java.util.Set<String> SUPPORTED_METHODS =
            java.util.Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    public ResolvedPolicy {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(keyBy, "keyBy");
        Objects.requireNonNull(rule, "rule");
    }

    /**
     * Validates and compiles raw policy fields.
     *
     * @throws InvalidPolicyException if the pattern does not parse or the limits are out of range
     */
    public static ResolvedPolicy compile(Long id, String name, String httpMethod, String endpointPattern, Tier tier,
            KeyBy keyBy, AlgorithmType algorithm, long limit, long windowMs, Integer refillTokens,
            FailureMode failureMode, int priority, int version) {
        if (id == null || id <= 0) {
            throw new InvalidPolicyException("id", "policy id must be positive");
        }
        RateLimitRule rule;
        try {
            // refill_tokens is NULL for window algorithms and optional for the bucket (defaults to limit)
            rule = new RateLimitRule(algorithm, limit, windowMs, refillTokens == null ? limit : refillTokens);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidPolicyException("limits", e.getMessage());
        }
        if (httpMethod != null && !SUPPORTED_METHODS.contains(httpMethod)) {
            throw new InvalidPolicyException("httpMethod", "unsupported HTTP method: " + httpMethod);
        }
        HttpMethod method = httpMethod == null ? null : HttpMethod.valueOf(httpMethod);
        return new ResolvedPolicy(id, name, method, parsePattern(endpointPattern), tier, keyBy, rule,
                failureMode, priority, version);
    }

    /**
     * Parses a policy's endpoint pattern. Supported: literal segments, {@code ?}, {@code *}, {@code **},
     * {@code {var}} and {@code {*var}}. Rejected: {@code {var:regex}}, because an administrator-supplied regular
     * expression would be evaluated against the path of every request (a costly regex is a CPU risk; security
     * review SEC-3).
     */
    public static PathPattern parsePattern(String endpointPattern) {
        if (endpointPattern == null || !endpointPattern.startsWith("/")) {
            throw new InvalidPolicyException("endpointPattern", "must start with '/'");
        }
        if (containsRegexVariable(endpointPattern)) {
            throw new InvalidPolicyException("endpointPattern",
                    "regular expressions in path variables ({name:regex}) are not supported; use {name}, *, ** or ?");
        }
        try {
            return PARSER.parse(endpointPattern);
        } catch (RuntimeException e) {
            throw new InvalidPolicyException("endpointPattern", "invalid path pattern: " + e.getMessage());
        }
    }

    /**
     * True if any capture variable carries a regex: in Spring's syntax that is a ':' between a '{' and the
     * next '}' (a variable name itself cannot contain ':'). Unclosed braces are left to the parser to reject.
     */
    static boolean containsRegexVariable(String pattern) {
        int open = pattern.indexOf('{');
        while (open >= 0) {
            int close = pattern.indexOf('}', open);
            String variable = close < 0 ? pattern.substring(open) : pattern.substring(open, close);
            if (variable.indexOf(':') >= 0) {
                return true;
            }
            if (close < 0) {
                return false;
            }
            open = pattern.indexOf('{', close);
        }
        return false;
    }

    /** Does this policy apply to a request with this method, path and tier? */
    public boolean matches(HttpMethod method, PathContainer path, Tier callerTier) {
        return (httpMethod == null || httpMethod.equals(method))
                && (tier == null || tier == callerTier)
                && pattern.matches(path);
    }

    public Optional<FailureMode> failureModeOverride() {
        return Optional.ofNullable(failureMode);
    }
}
