package com.utkarsh.ratelimiter.demo;

import com.utkarsh.ratelimiter.identity.ClientIdentity;
import com.utkarsh.ratelimiter.identity.ClientIdentityResolver;
import com.utkarsh.ratelimiter.identity.Tier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal endpoints for demonstrating and load-testing rate limiting. They contain no business
 * logic and know nothing about rate limiting: the filter applies whichever policy matches.
 *
 * <table>
 *   <tr><th>Endpoint</th><th>Access</th><th>Policy (seed data)</th></tr>
 *   <tr><td>/public</td><td>anyone</td><td>anonymous-default (per IP) or the caller's tier policy</td></tr>
 *   <tr><td>/profile</td><td>any token</td><td>the caller's tier policy</td></tr>
 *   <tr><td>/premium</td><td>PREMIUM, ENTERPRISE</td><td>the caller's tier policy</td></tr>
 *   <tr><td>/enterprise</td><td>ENTERPRISE</td><td>demo-enterprise-endpoint (50/min, fail-closed)</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Demo")
public class DemoController {

    private final ClientIdentityResolver identities;

    public DemoController(ClientIdentityResolver identities) {
        this.identities = identities;
    }

    @GetMapping("/public")
    @Operation(summary = "Open to anonymous callers (limited per IP)")
    public Map<String, Object> publicEndpoint(HttpServletRequest request) {
        return body("public", identities.resolve(request));
    }

    @GetMapping("/profile")
    @Operation(summary = "Any authenticated caller")
    public Map<String, Object> profile(HttpServletRequest request) {
        return body("profile", identities.resolve(request));
    }

    @GetMapping("/premium")
    @Operation(summary = "PREMIUM or ENTERPRISE tier")
    public Map<String, Object> premium(HttpServletRequest request) {
        return body("premium", requireTier(request, EnumSet.of(Tier.PREMIUM, Tier.ENTERPRISE)));
    }

    @GetMapping("/enterprise")
    @Operation(summary = "ENTERPRISE tier")
    public Map<String, Object> enterprise(HttpServletRequest request) {
        return body("enterprise", requireTier(request, EnumSet.of(Tier.ENTERPRISE)));
    }

    private ClientIdentity requireTier(HttpServletRequest request, Set<Tier> allowed) {
        ClientIdentity identity = identities.resolve(request);
        if (!allowed.contains(identity.tier())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires tier " + allowed);
        }
        return identity;
    }

    private static Map<String, Object> body(String endpoint, ClientIdentity identity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint", endpoint);
        body.put("userId", identity.userId());
        body.put("tier", identity.tier());
        return body;
    }
}
