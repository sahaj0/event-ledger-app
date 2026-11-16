package com.eventledger.gateway.filter;

import com.eventledger.gateway.config.RateLimitProperties;
import com.eventledger.gateway.dto.response.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Token-bucket style limit (Resilience4j RateLimiter) per client address on {@code POST /events}.
 * Over the limit the request is refused immediately with 429 + Retry-After, before any database or
 * Account Service work is done. Reads, /health and /actuator are not limited.
 *
 * <p>The client key is the TCP peer address. X-Forwarded-For is deliberately NOT trusted (it is
 * client-controlled); behind a trusted proxy, configure the container's forwarded-header handling instead.
 * Limiters are kept in memory per address - fine for this service; a production edge would bound that map.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE - 10)   // after the observation + trace-id filters, so 429s carry a trace id
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiterRegistry registry;
    private final RateLimiterConfig config;
    private final Duration refreshPeriod;
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimitProperties props, Tracer tracer, MeterRegistry meters, ObjectMapper mapper) {
        this.refreshPeriod = props.refreshPeriod();
        this.config = RateLimiterConfig.custom()
                .limitForPeriod(props.limitForPeriod())
                .limitRefreshPeriod(props.refreshPeriod())
                .timeoutDuration(Duration.ZERO)          // never queue the caller: allow or reject now
                .build();
        this.registry = RateLimiterRegistry.of(config);
        this.tracer = tracer;
        this.meters = meters;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/events".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        RateLimiter limiter = registry.rateLimiter("client-" + client, config);
        if (limiter.acquirePermission()) {
            chain.doFilter(request, response);
            return;
        }

        meters.counter("gateway.ratelimit.rejected").increment();
        log.warn("Rate limit exceeded for client {} on POST /events", client);
        long retryAfter = Math.max(1, (long) Math.ceil(refreshPeriod.toMillis() / 1000.0));
        var span = tracer.currentSpan();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), 429, "Too Many Requests",
                "Rate limit exceeded; retry after " + retryAfter + "s", request.getRequestURI(),
                span == null ? null : span.context().traceId(), List.of()));
    }
}
