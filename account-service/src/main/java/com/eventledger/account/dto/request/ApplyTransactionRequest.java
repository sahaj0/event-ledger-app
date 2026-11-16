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

    /** The pattern above checks the shape; this checks it is a real ISO 4217 code (so "ZZZ" is refused). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @AssertTrue(message = "is not a recognised ISO 4217 currency code")
    public boolean isCurrencyKnown() {
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            return true;
        }
        try {
            java.util.Currency.getInstance(currency);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
