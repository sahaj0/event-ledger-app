package com.eventledger.gateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** HTTP contract: response of the apply-transaction call (unknown fields are ignored for forward compatibility). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountTransactionResponse(BigDecimal balance, boolean duplicate) {
}
