package com.eventledger.gateway.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * The Gateway's own record of a received event.
 *
 * <p>A surrogate key plus a UNIQUE constraint on {@code event_id} is used (rather than making
 * eventId the primary key) so that a racing duplicate INSERT fails loudly on the constraint
 * instead of being silently turned into an UPDATE by JPA's merge.
 */
@Entity
@Table(name = "events",
        uniqueConstraints = @UniqueConstraint(name = "uk_events_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_events_account_ts", columnList = "account_id, event_timestamp"))
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "account_id", nullable = false, length = 100)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EventType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Convert(converter = MetadataConverter.class)
    @Column(length = 4000)
    private Map<String, Object> metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EventStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    protected Event() {
    }

    public Event(String eventId, String accountId, EventType type, BigDecimal amount, String currency,
                 Instant eventTimestamp, Map<String, Object> metadata) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.status = EventStatus.PENDING;
        this.receivedAt = Instant.now();
    }

    /** True when a re-submission carries the same business content as this stored event. */
    public boolean sameContentAs(String accountId, EventType type, BigDecimal amount, String currency,
                                 Instant eventTimestamp) {
        return this.accountId.equals(accountId) && this.type == type
                && this.amount.compareTo(amount) == 0 && this.currency.equals(currency)
                && this.eventTimestamp.equals(eventTimestamp);
    }

    public void markApplied() {
        this.status = EventStatus.APPLIED;
        this.appliedAt = Instant.now();
        this.failureReason = null;
    }

    public void markRejected(String reason) {
        this.status = EventStatus.REJECTED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public EventType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public Map<String, Object> getMetadata() { return metadata; }
    public EventStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getAppliedAt() { return appliedAt; }
}
