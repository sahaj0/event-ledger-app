package com.eventledger.account.dto.response;

import com.eventledger.account.entity.LedgerTransaction;
import com.eventledger.account.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant appliedAt) {

    public static TransactionResponse from(LedgerTransaction t) {
        return new TransactionResponse(t.getEventId(), t.getAccountId(), t.getType(),
                Amounts.display(t.getAmount()), t.getCurrency(), t.getEventTimestamp(), t.getAppliedAt());
    }
}
