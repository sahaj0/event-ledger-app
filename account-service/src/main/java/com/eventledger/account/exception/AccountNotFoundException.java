package com.eventledger.account.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountId) {
        super("Account '" + accountId + "' not found");
    }
}
