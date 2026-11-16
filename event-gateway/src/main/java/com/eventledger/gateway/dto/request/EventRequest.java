package com.eventledger.gateway.dto.request;

import com.eventledger.gateway.entity.EventType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Body of {@code POST /events}. */
public record EventRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 100) String accountId,
        @NotNull EventType type,
        @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter upper-case ISO currency code")
        String currency,
        @NotNull Instant eventTimestamp,
        Map<String, Object> metadata) {
}
