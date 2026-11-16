package com.eventledger.account.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One applied transaction. The ledger is append-only; the balance is always derived from it,
 * so arrival order can never corrupt it.
 *
 * <p>{@code eventId} carries a UNIQUE constraint: that is the hard idempotency guarantee, even
 * when two identical requests race each other.
 */
@Entity
@Table(name = "ledger_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_ledger_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_ledger_account_ts", columnList = "account_id, event_timestamp"))
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "account_id", nullable = false, length = 100)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    protected LedgerTransaction() {
    }

    public LedgerTransaction(String eventId, String accountId, TransactionType type, BigDecimal amount,
                             String currency, Instant eventTimestamp) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.appliedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public Instant getAppliedAt() { return appliedAt; }
}
