package com.utkarsh.ratelimiter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utkarsh.ratelimiter.algorithm.AlgorithmRegistry;
import com.utkarsh.ratelimiter.filter.RateLimitFilter;
import com.utkarsh.ratelimiter.filter.RateLimitFilterProperties;
import com.utkarsh.ratelimiter.filter.RateLimitResponseWriter;
import com.utkarsh.ratelimiter.identity.ClientIdentityResolver;
import com.utkarsh.ratelimiter.metrics.RateLimitTelemetry;
import com.utkarsh.ratelimiter.policy.PolicyResolver;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RateLimitFilterConfiguration {

    @Bean
    RateLimitFilter rateLimitFilter(ClientIdentityResolver identities, PolicyResolver policies, AlgorithmRegistry algorithms,
                                    ObjectMapper objectMapper, RateLimitFilterProperties filterProperties,
                                    RateLimiterProperties properties, RateLimitTelemetry telemetry) {
        return new RateLimitFilter(identities, policies, algorithms, new RateLimitResponseWriter(objectMapper),
                filterProperties, properties.defaultFailureMode(), telemetry);
    }

    /**
     * Spring Boot registers every Filter bean with the servlet container automatically. This one must run
     * only at its position inside the security chain, so the automatic registration is disabled;
     * otherwise each request would be counted twice.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterServletRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
