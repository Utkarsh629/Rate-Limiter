package com.utkarsh.ratelimiter.identity;

import com.utkarsh.ratelimiter.security.TokenClaims;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Determines the caller's identity after Spring Security has run.
 *
 * <ul>
 *   <li><b>User id</b>: the {@code sub} claim of a validated JWT. Never taken from headers or parameters.</li>
 *   <li><b>Tier</b>: the {@code tier} claim. Missing or unrecognised values, and {@code ANONYMOUS},
 *       map to FREE: the least-privileged authenticated tier, so a malformed claim never grants more.</li>
 *   <li><b>Client IP</b>: {@link HttpServletRequest#getRemoteAddr()}. Tomcat's RemoteIpValve rewrites it
 *       from X-Forwarded-For <b>only</b> when the TCP peer matches {@code server.tomcat.remoteip.internal-proxies}
 *       ({@code TRUSTED_PROXIES}, empty by default = trust no proxy). Headers are never read here directly.</li>
 * </ul>
 */
@Component
public class ClientIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIdentityResolver.class);

    public ClientIdentity resolve(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            String userId = jwt.getToken().getSubject();
            if (userId != null && !userId.isBlank()) {
                return new ClientIdentity(userId, tierFrom(jwt.getToken().getClaimAsString(TokenClaims.TIER)), clientIp);
            }
            log.warn("Authenticated token without a subject; treating caller as anonymous");
        }
        return ClientIdentity.anonymous(clientIp);
    }

    public static Tier tierFrom(String claim) {
        if (claim == null) {
            return Tier.FREE;
        }
        try {
            Tier tier = Tier.valueOf(claim);
            return tier == Tier.ANONYMOUS ? Tier.FREE : tier;
        } catch (IllegalArgumentException e) {
            return Tier.FREE;
        }
    }
}
