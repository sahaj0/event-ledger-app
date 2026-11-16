package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * HTTP contract: body of {@code POST {account-service}/accounts/{accountId}/transactions}.
 * Deliberately a separate copy from the Account Service's own class: the two services share
 * the wire format, not code.
 */
public record AccountTransactionRequest(
        String eventId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {
}
