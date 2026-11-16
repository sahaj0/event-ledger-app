package com.eventledger.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Safety net for events left PENDING by an Account Service outage: every {@code interval} it re-sends events
 * that have been PENDING for at least {@code min-age}. The min-age keeps it from racing a client's own retry of
 * a brand-new event; if both do happen to send, the Account Service's idempotency makes it harmless.
 * It goes through the same retry + circuit breaker as everything else, so while the breaker is open it is a no-op.
 *
 * <p>Disable with {@code gateway.redrive.enabled=false}.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "gateway.redrive", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingEventRedriver {

    private final EventService eventService;
    private final Duration minAge;
    private final int batchSize;

    public PendingEventRedriver(EventService eventService,
                                @Value("${gateway.redrive.min-age:30s}") Duration minAge,
                                @Value("${gateway.redrive.batch-size:100}") int batchSize) {
        this.eventService = eventService;
        this.minAge = minAge;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${gateway.redrive.interval:15s}",
            initialDelayString = "${gateway.redrive.interval:15s}")
    public void redrive() {
        eventService.redrivePending(minAge, batchSize);
    }
}
