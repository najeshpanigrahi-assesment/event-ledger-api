package com.eventledger.dto;

import com.eventledger.model.TransactionEvent;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Transaction event response")
public final class EventResponse {

    @Schema(example = "evt-001")
    private String eventId;

    @Schema(example = "acct-123")
    private String accountId;

    @Schema(example = "CREDIT")
    private String type;

    @Schema(example = "150.00")
    private BigDecimal amount;

    @Schema(example = "USD")
    private String currency;

    @Schema(example = "2026-05-15T14:02:11Z")
    private Instant eventTimestamp;

    @Schema(example = "2026-05-15T14:05:00Z")
    private Instant receivedAt;

    @Schema(description = "Optional metadata provided at submission")
    private Map<String, Object> metadata;
    public static EventResponse from(TransactionEvent event) {
        return EventResponse.builder()
                .eventId(event.getEventId())
                .accountId(event.getAccountId())
                .type(event.getType().name())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp())
                .receivedAt(event.getReceivedAt())
                .metadata(event.getMetadata())
                .build();
    }
}
