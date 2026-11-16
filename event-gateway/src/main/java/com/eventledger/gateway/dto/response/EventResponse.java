package com.eventledger.gateway.dto.response;

import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.entity.EventType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        EventStatus status,
        String failureReason,
        Instant receivedAt,
        Instant appliedAt) {

    public static EventResponse from(Event e) {
        return new EventResponse(e.getEventId(), e.getAccountId(), e.getType(), display(e.getAmount()),
                e.getCurrency(), e.getEventTimestamp(), e.getMetadata(), e.getStatus(), e.getFailureReason(),
                e.getReceivedAt(), e.getAppliedAt());
    }

    /** 150.0000 -> 150.00 (presentation only). */
    private static BigDecimal display(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 2 ? s.setScale(2) : s;
    }
}
