package com.eventledger.controller;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.ErrorResponse;
import com.eventledger.dto.EventPageResponse;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.model.TransactionEvent;
import com.eventledger.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Event Ledger", description = "Financial transaction event processing API")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    // ── POST /events ──────────────────────────────────────────────────────────

    @Operation(
        summary = "Submit a transaction event",
        description = """
                Submits a new financial transaction event.
                **Idempotent**: re-submitting the same `eventId` returns the original event
                with HTTP 200 instead of creating a duplicate or returning an error.
                Concurrent submissions of the same `eventId` are serialised safely.
                """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Event created successfully",
                     content = @Content(schema = @Schema(implementation = EventResponse.class))),
        @ApiResponse(responseCode = "200", description = "Duplicate — original event returned",
                     content = @Content(schema = @Schema(implementation = EventResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/events")
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        TransactionEvent saved = eventService.submitEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(EventResponse.from(saved));
    }

    // ── GET /events/{id} ─────────────────────────────────────────────────────

    @Operation(summary = "Retrieve a single event by its ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event found",
                     content = @Content(schema = @Schema(implementation = EventResponse.class))),
        @ApiResponse(responseCode = "404", description = "Event not found",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/events/{id}")
    public ResponseEntity<EventResponse> getEvent(
            @Parameter(description = "Unique event ID", example = "evt-001")
            @PathVariable String id) {
        return ResponseEntity.ok(EventResponse.from(eventService.getEventById(id)));
    }

    // ── GET /events?account=…[&page=…&size=…] ────────────────────────────────

    @Operation(
        summary = "List events for an account",
        description = """
                Returns all events for the given account ordered chronologically by
                `eventTimestamp` ASC, regardless of the order they were received.

                Add `page` and `size` query parameters to get a paginated response.
                Without them the full list is returned.
                """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Events retrieved",
                     content = @Content(array = @ArraySchema(schema = @Schema(implementation = EventResponse.class))))
    })
    @GetMapping("/events")
    public ResponseEntity<?> listEvents(
            @Parameter(description = "Account ID", required = true, example = "acct-123")
            @RequestParam String account,

            @Parameter(description = "0-based page index (omit for full list)")
            @RequestParam(required = false) Integer page,

            @Parameter(description = "Page size 1–100 (omit for full list)")
            @RequestParam(required = false) @Min(1) @Max(100) Integer size) {

        if (page != null && size != null) {
            return ResponseEntity.ok(eventService.getEventsByAccountPaginated(account, page, size));
        }

        List<EventResponse> events = eventService.getEventsByAccount(account)
                .stream()
                .map(EventResponse::from)
                .toList();
        return ResponseEntity.ok(events);
    }

    // ── GET /accounts/{accountId}/balance ─────────────────────────────────────

    @Operation(
        summary = "Get the current computed balance for an account",
        description = "balance = sum(CREDIT amounts) − sum(DEBIT amounts). " +
                      "Always accurate regardless of event arrival order."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Balance returned",
                     content = @Content(schema = @Schema(implementation = BalanceResponse.class))),
        @ApiResponse(responseCode = "404", description = "Account not found",
                     content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @Parameter(description = "Account ID", example = "acct-123")
            @PathVariable String accountId) {
        return ResponseEntity.ok(eventService.getAccountBalance(accountId));
    }
}
