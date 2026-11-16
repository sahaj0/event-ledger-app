package com.eventledger.account.exception;

public class EventConflictException extends RuntimeException {
    public EventConflictException(String eventId, String otherAccountId) {
        super("eventId '" + eventId + "' was already applied to a different account ('" + otherAccountId + "')");
    }
}
