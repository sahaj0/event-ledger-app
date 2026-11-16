package com.eventledger.gateway.dto.request;

import com.eventledger.gateway.entity.EventType;
import com.eventledger.gateway.entity.MetadataConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Metadata is stored as JSON in a fixed-size column: refuse oversized input with a 400, never a 500. */
    @JsonIgnore
    @AssertTrue(message = "is too large (at most " + MetadataConverter.MAX_LENGTH + " characters as JSON)")
    public boolean isMetadataWithinLimit() {
        if (metadata == null || metadata.isEmpty()) {
            return true;
        }
        try {
            return JSON.writeValueAsString(metadata).length() <= MetadataConverter.MAX_LENGTH;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
