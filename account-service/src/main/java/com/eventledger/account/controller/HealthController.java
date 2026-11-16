package com.eventledger.account.controller;

import com.eventledger.account.repository.LedgerTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final DataSource dataSource;
    private final LedgerTransactionRepository repository;
    private final String serviceName;

    public HealthController(DataSource dataSource, LedgerTransactionRepository repository,
                            @Value("${spring.application.name}") String serviceName) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> db = new LinkedHashMap<>();
        boolean up;
        try (var conn = dataSource.getConnection()) {
            up = conn.isValid(2);
            db.put("status", up ? "UP" : "DOWN");
            db.put("product", conn.getMetaData().getDatabaseProductName());
            db.put("transactions", repository.count());
        } catch (Exception e) {
            up = false;
            db.put("status", "DOWN");
            db.put("error", e.getClass().getSimpleName());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("service", serviceName);
        body.put("timestamp", Instant.now().toString());
        body.put("checks", Map.of("database", db));
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
