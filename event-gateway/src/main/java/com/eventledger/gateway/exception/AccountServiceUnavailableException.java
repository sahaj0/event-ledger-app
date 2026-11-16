package com.eventledger.gateway.exception;

/** The Account Service is down, slow, or the circuit breaker is open. Mapped to HTTP 503. */
public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
