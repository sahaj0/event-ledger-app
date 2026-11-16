package com.eventledger.account.dto.response;

import java.math.BigDecimal;

/** Contract: response of {@code POST /accounts/{accountId}/transactions}. */
public record ApplyTransactionResponse(
        TransactionResponse transaction,
        BigDecimal balance,
        boolean duplicate) {

    public static ApplyTransactionResponse of(TransactionResponse tx, BigDecimal balance, boolean duplicate) {
        return new ApplyTransactionResponse(tx, Amounts.display(balance), duplicate);
    }
}
