package com.eventledger.gateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** HTTP contract: response of {@code GET /accounts/{accountId}/balance}; also returned as-is by the Gateway. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountBalance(String accountId, BigDecimal balance, String currency) {
}
