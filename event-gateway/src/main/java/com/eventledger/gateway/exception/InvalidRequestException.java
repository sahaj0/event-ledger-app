package com.eventledger.gateway.exception;

/** The request parameters are not acceptable (e.g. a page size of 0). Mapped to HTTP 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
