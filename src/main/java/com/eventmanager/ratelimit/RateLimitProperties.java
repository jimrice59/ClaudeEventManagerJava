package com.eventmanager.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bound from {@code rate-limit.*} in application.yml (overridable via {@code RATE_LIMIT_ENABLED},
 * {@code RATE_LIMIT_WINDOW_DURATION}, {@code RATE_LIMIT_MAX_REQUESTS} env vars). Defaults to 1000
 * requests per rolling 1-second window — see {@link RateLimiter} for how the window is enforced.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Duration windowDuration = Duration.ofSeconds(1);
    private int maxRequests = 1000;
}
