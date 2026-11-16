package com.eventledger.account.service;

import com.eventledger.account.dto.request.ApplyTransactionRequest;
import com.eventledger.account.dto.response.AccountDetailsResponse;
import com.eventledger.account.dto.response.ApplyTransactionResponse;
import com.eventledger.account.dto.response.BalanceResponse;

public interface AccountService {

    /** Idempotent: re-applying the same eventId returns the original result and changes nothing. */
    ApplyTransactionResponse applyTransaction(String accountId, ApplyTransactionRequest request);

    BalanceResponse getBalance(String accountId);

    AccountDetailsResponse getAccount(String accountId);
}
