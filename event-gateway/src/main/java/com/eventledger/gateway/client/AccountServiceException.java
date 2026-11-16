package com.eventledger.gateway.client;

/** The Account Service could not be reached or failed (I/O error, timeout, 5xx). Retryable; counts against the breaker. */
public class AccountServiceException extends RuntimeException {
    public AccountServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
