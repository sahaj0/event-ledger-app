package com.eventledger.gateway.dto.response;

/** {@code created} is true for a first-time submission, false for an idempotent replay. */
public record SubmitResult(EventResponse event, boolean created) {
}
