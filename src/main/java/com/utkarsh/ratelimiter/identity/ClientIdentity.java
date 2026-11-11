package com.utkarsh.ratelimiter.identity;

import java.util.Objects;

/**
 * Who is calling. {@code userId} comes only from a validated JWT; {@code clientIp} is the socket
 * peer, or the X-Forwarded-For client when the peer is a configured trusted proxy.
 *
 * @param userId null for anonymous callers
 */
public record ClientIdentity(String userId, Tier tier, String clientIp) {

    public ClientIdentity {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(clientIp, "clientIp");
        if (userId == null && tier != Tier.ANONYMOUS) {
            throw new IllegalArgumentException("anonymous callers must have tier ANONYMOUS");
        }
        if (userId != null && tier == Tier.ANONYMOUS) {
            throw new IllegalArgumentException("authenticated callers cannot have tier ANONYMOUS");
        }
    }

    public static ClientIdentity anonymous(String clientIp) {
        return new ClientIdentity(null, Tier.ANONYMOUS, clientIp);
    }

    public boolean authenticated() {
        return userId != null;
    }
}
