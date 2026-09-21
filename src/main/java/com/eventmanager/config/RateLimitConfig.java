package com.eventmanager.config;

import com.eventmanager.ratelimit.RateLimitFilter;
import com.eventmanager.ratelimit.RateLimitProperties;
import com.eventmanager.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RateLimitFilter} as a plain {@link FilterRegistrationBean} — deliberately not
 * a {@code @Component} on the filter class itself, which would cause Spring Boot to also
 * auto-register it a second time via component scanning — with {@link Ordered#HIGHEST_PRECEDENCE},
 * so it runs ahead of every Spring Security filter chain (@Order 1-4 in SecurityConfig /
 * AuthorizationServerConfig, all registered well after Boot's default filter order of -100).
 */
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(rateLimiter, rateLimitProperties));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
