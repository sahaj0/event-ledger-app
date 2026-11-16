package com.eventledger.gateway.exception;

/** The same eventId was re-submitted with different content: not a retry, a client bug. */
public class EventConflictException extends RuntimeException {
    public EventConflictException(String eventId) {
        super("eventId '" + eventId + "' already exists with different content");
    }
}
