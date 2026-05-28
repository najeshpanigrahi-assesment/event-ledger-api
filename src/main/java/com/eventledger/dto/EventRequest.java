package com.eventledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for submitting a transaction event")
public final class EventRequest {

    @NotBlank(message = "eventId is required")
    @Schema(description = "Unique identifier for the event", example = "evt-001")
    private String eventId;

    @NotBlank(message = "accountId is required")
    @Schema(description = "The account this event belongs to", example = "acct-123")
    private String accountId;

    @NotBlank(message = "type is required")
    @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
    @Schema(description = "Transaction type", example = "CREDIT", allowableValues = {"CREDIT", "DEBIT"})
    private String type;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
    @Schema(description = "Transaction amount, must be > 0", example = "150.00")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
    @Schema(description = "ISO 4217 currency code", example = "USD")
    private String currency;

    @NotNull(message = "eventTimestamp is required")
    @Schema(description = "ISO 8601 timestamp of when the event originally occurred",
            example = "2026-05-15T14:02:11Z")
    private Instant eventTimestamp;

    @Schema(description = "Optional additional context metadata")
    private Map<String, Object> metadata;
}
