package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountBalance;
import com.eventledger.gateway.dto.request.EventRequest;
import com.eventledger.gateway.dto.response.EventResponse;
import com.eventledger.gateway.dto.response.SubmitResult;

import java.util.List;

public interface EventService {

    /** Idempotent on eventId. Throws AccountServiceUnavailableException when the event could not be applied yet. */
    SubmitResult submit(EventRequest request);

    EventResponse getByEventId(String eventId);

    /** Local data only - works while the Account Service is down. Ordered by eventTimestamp. */
    List<EventResponse> listForAccount(String accountId);

    /**
     * Re-sends PENDING events that are at least {@code minAge} old, oldest first, up to {@code batchSize}.
     * Stops at the first outage (no point hammering a down service). Returns how many were applied.
     */
    int redrivePending(java.time.Duration minAge, int batchSize);

    /** Needs the Account Service; throws AccountServiceUnavailableException when it is unreachable. */
    AccountBalance getBalance(String accountId);
}
