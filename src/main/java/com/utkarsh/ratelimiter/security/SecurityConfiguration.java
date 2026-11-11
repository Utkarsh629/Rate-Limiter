package com.utkarsh.ratelimiter.security;

import java.util.Base64;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import com.utkarsh.ratelimiter.filter.RateLimitFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security.
 *
 * <ul>
 *   <li>Tokens are HS256 JWTs. Signature, {@code exp}/{@code nbf} (with clock skew) and {@code iss}
 *       are validated; any failure is 401, including on endpoints that also allow anonymous access:
 *       a bad token is never silently downgraded to "anonymous".</li>
 *   <li>Policy administration requires {@code ROLE_ADMIN} (claim {@code roles: ["ADMIN"]}).</li>
 *   <li>{@code /api/v1/demo/public} allows anonymous callers (identified and rate-limited by IP); the
 *       other demo endpoints require a token.</li>
 *   <li>{@link RateLimitFilter} sits directly after {@link BearerTokenAuthenticationFilter}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)          // no cookies/sessions: CSRF does not apply
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/flyway").permitAll()
                        // Scraped by Prometheus on the internal network; Nginx blocks /actuator/** externally (P2).
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/demo-token").permitAll()
                        .requestMatchers("/api/v1/policies/**", "/api/v1/policies").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/demo/public", "/api/v1/demo/whoami").permitAll()
                        .requestMatchers("/api/v1/demo/**").authenticated()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Rate limiting runs after the bearer token is validated (identity known) and before
                // authorization (refused requests still consume quota).
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(properties.clockSkew()),
                new JwtIssuerValidator(properties.issuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** Decodes and checks the configured secret; a missing or short key fails startup. */
    public static SecretKey signingKey(JwtProperties properties) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.secret());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ratelimiter.security.jwt.secret (JWT_SECRET) must be base64", e);
        }
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MIN_SECRET_BYTES
                    + " bytes for HS256; got " + key.length);
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::roles);
        return converter;
    }

    private static Collection<GrantedAuthority> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
        if (roles == null) {
            return List.of();
        }
        return roles.stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
}
