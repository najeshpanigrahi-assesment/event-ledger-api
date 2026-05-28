package com.eventledger.exception;

import com.eventledger.model.TransactionEvent;
import lombok.Getter;

/**
 * Thrown when an event with the same eventId already exists.
 * Carries the original entity so the handler can return it to the caller.
 */
@Getter
public class DuplicateEventException extends RuntimeException {

    private final TransactionEvent existingEvent;

    public DuplicateEventException(String eventId, TransactionEvent existingEvent) {
        super("Event already exists with id: " + eventId);
        this.existingEvent = existingEvent;
    }
}
