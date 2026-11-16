package com.eventledger.gateway.controller;

import com.eventledger.gateway.repository.EventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UP     - database fine, Account Service circuit closed.
 * DEGRADED - gateway is serving (reads work) but the Account Service circuit is not closed. Still HTTP 200:
 *            the Gateway process itself is healthy, so an orchestrator must not restart it.
 * DOWN   - the Gateway's own database is unavailable (HTTP 503).
 */
@RestController
public class HealthController {

    private final DataSource dataSource;
    private final EventRepository repository;
    private final CircuitBreakerRegistry circuitBreakers;
    private final String serviceName;

    public HealthController(DataSource dataSource, EventRepository repository,
                            CircuitBreakerRegistry circuitBreakers,
                            @Value("${spring.application.name}") String serviceName) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.circuitBreakers = circuitBreakers;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> db = new LinkedHashMap<>();
        boolean dbUp;
        try (var conn = dataSource.getConnection()) {
            dbUp = conn.isValid(2);
            db.put("status", dbUp ? "UP" : "DOWN");
            db.put("product", conn.getMetaData().getDatabaseProductName());
            db.put("events", repository.count());
        } catch (Exception e) {
            dbUp = false;
            db.put("status", "DOWN");
            db.put("error", e.getClass().getSimpleName());
        }

        var breaker = circuitBreakers.circuitBreaker("account");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("circuitBreaker", breaker.getState().name());
        account.put("failureRate", breaker.getMetrics().getFailureRate());
        boolean closed = breaker.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED
                || breaker.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN;

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", db);
        checks.put("accountService", account);

        String status = !dbUp ? "DOWN" : (closed ? "UP" : "DEGRADED");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("service", serviceName);
        body.put("timestamp", Instant.now().toString());
        body.put("checks", checks);
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
