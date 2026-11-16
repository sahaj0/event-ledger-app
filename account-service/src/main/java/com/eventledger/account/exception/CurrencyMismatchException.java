package com.eventledger.account.exception;

public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String accountId, String accountCurrency, String requested) {
        super("Account '" + accountId + "' holds " + accountCurrency + "; cannot apply a " + requested
                + " transaction");
    }
}
