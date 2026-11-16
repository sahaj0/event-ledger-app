package com.eventledger.gateway.client;

/** The Account Service answered with a 4xx: a definitive "no". Never retried and not counted as an outage. */
public class AccountRejectedException extends RuntimeException {

    private final int status;

    public AccountRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
