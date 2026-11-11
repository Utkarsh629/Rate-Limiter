package com.utkarsh.ratelimiter.demo;

import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.ClientIdentityResolver;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEVELOPMENT ONLY: shows how the service sees the caller (identity, client IP) and which policy
 * and quota key would apply to a given request. Lets identity and policy resolution be checked end
 * to end before the rate-limiting filter exists. Exposes policy internals, hence dev-only.
 */
@RestController
@Profile("!prod")
@ConditionalOnProperty(prefix = "ratelimiter.dev-endpoints", name = "enabled", havingValue = "true")
@Tag(name = "Development only")
public class IdentityEchoController {

    private final ClientIdentityResolver identities;
    private final PolicyResolver policies;

    public IdentityEchoController(ClientIdentityResolver identities, PolicyResolver policies) {
        this.identities = identities;
        this.policies = policies;
    }

    @GetMapping("/api/v1/demo/whoami")
    @Operation(summary = "DEV ONLY: resolved identity and the policy that would apply to method+path")
    public Map<String, Object> whoami(HttpServletRequest request,
                                      @RequestParam(defaultValue = "GET") String method,
                                      @RequestParam(defaultValue = "/api/v1/demo/hello") String path) {
        ClientIdentity identity = identities.resolve(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", identity.userId());
        body.put("tier", identity.tier());
        body.put("clientIp", identity.clientIp());
        policies.resolve(HttpMethod.valueOf(method), path, identity).ifPresentOrElse(target -> {
            body.put("policyId", target.policy().id());
            body.put("policyName", target.policy().name());
            body.put("algorithm", target.policy().rule().algorithm());
            body.put("limit", target.policy().rule().limit());
            body.put("windowMs", target.policy().rule().windowMillis());
            body.put("quotaSubject", target.key().subjectType() + ":" + target.key().subject());
        }, () -> body.put("policyName", null));
        return body;
    }
}
