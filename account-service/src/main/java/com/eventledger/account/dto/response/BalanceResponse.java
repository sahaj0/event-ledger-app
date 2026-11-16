package com.eventledger.account.dto.response;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance, String currency) {
    public static BalanceResponse of(String accountId, BigDecimal balance, String currency) {
        return new BalanceResponse(accountId, Amounts.display(balance), currency);
    }
}
