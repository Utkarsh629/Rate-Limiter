package com.utkarsh.ratelimiter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.utkarsh.ratelimiter.support.ApiTestSupport;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

/**
 * With the TCP peer (this test, on loopback) configured as a trusted proxy, Tomcat's RemoteIpValve
 * takes the client IP from X-Forwarded-For: the right-most address that is not itself a trusted
 * proxy. Addresses further left are client-supplied and therefore ignored.
 * The opposite case (no trusted proxies: header ignored) is in IdentityAndResolutionIT.
 */
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1")
class TrustedProxyIT extends ApiTestSupport {

    @ParameterizedTest(name = "X-Forwarded-For: {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "203.0.113.9                         | 203.0.113.9",
            "6.6.6.6, 203.0.113.9                | 203.0.113.9",   // spoofed left-most entry ignored
            "6.6.6.6, 203.0.113.9, 127.0.0.1     | 203.0.113.9",   // trusted hops are skipped
    })
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clientIpComesFromTheRightMostUntrustedHop(String forwardedFor, String expectedClientIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", forwardedFor);

        Map<String, Object> me = http.exchange("/api/v1/demo/whoami", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();

        assertThat(me).containsEntry("clientIp", expectedClientIp).containsEntry("quotaSubject", "IP:" + expectedClientIp);
    }
}
