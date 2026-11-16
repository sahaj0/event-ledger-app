package com.eventledger.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Per-client rate limit applied to {@code POST /events} (the only write path).
 * Defaults: 50 submissions per second per client address.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(Boolean enabled, Integer limitForPeriod, Duration refreshPeriod) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        limitForPeriod = limitForPeriod == null ? 50 : limitForPeriod;
        refreshPeriod = refreshPeriod == null ? Duration.ofSeconds(1) : refreshPeriod;
    }
}
