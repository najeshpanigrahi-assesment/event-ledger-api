package com.eventledger.exception;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {

        super("Event not found with id: " + eventId);
    }
}
