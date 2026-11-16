package com.eventledger.account.dto.request;

import com.eventledger.account.entity.TransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Contract: body of {@code POST /accounts/{accountId}/transactions}. */
public record ApplyTransactionRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotNull TransactionType type,
        @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter upper-case ISO currency code")
        String currency,
        @NotNull Instant eventTimestamp) {
}
