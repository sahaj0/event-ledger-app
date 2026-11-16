package com.eventledger.account.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record AccountDetailsResponse(
        String accountId,
        String currency,
        BigDecimal balance,
        long transactionCount,
        List<TransactionResponse> recentTransactions) {

    public static AccountDetailsResponse of(String accountId, String currency, BigDecimal balance,
                                            long count, List<TransactionResponse> recent) {
        return new AccountDetailsResponse(accountId, currency, Amounts.display(balance), count, recent);
    }
}
