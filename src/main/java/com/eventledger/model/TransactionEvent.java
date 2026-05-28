package com.eventledger.model;

import com.eventledger.enums.EventType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * JPA entity that persists a single financial transaction event.
 * <p>
 * Design decisions:
 * <ul>
 *   <li>eventId is the natural primary key — guarantees DB-level idempotency.</li>
 *   <li>metadata is stored as a plain JSON text column compatible with H2.</li>
 *   <li>eventTimestamp is the business timestamp; receivedAt tracks arrival time.</li>
 * </ul>
 */
@Entity
@Table(
    name = "transaction_events",
    indexes = {
        @Index(name = "idx_te_account_id",        columnList = "account_id"),
        @Index(name = "idx_te_event_timestamp",   columnList = "event_timestamp")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 6)
    private EventType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /**
     * Stored as JSON text — works with H2 and all major RDBMS.
     * The Map is serialised/deserialised by Hibernate via the JSON type handler.
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    @Convert(converter = com.eventledger.model.JsonMetadataConverter.class)
    private Map<String, Object> metadata;

}
