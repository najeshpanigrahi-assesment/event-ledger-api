package com.eventledger.service;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.EventPageResponse;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.enums.EventType;
import com.eventledger.exception.AccountNotFoundException;
import com.eventledger.exception.DuplicateEventException;
import com.eventledger.exception.EventNotFoundException;
import com.eventledger.model.TransactionEvent;
import com.eventledger.repository.TransactionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core business logic for event submission, retrieval, and balance calculation.
 *
 * <h3>Idempotency strategy</h3>
 * The database primary key is {@code eventId}. A duplicate submission triggers a
 * unique-constraint violation at the DB level. We additionally use per-key in-memory
 * locks so that two simultaneous requests for the same {@code eventId} are serialised
 * before either reaches the database — the winner inserts, the loser reads the
 * already-persisted record and gets HTTP 200 with the original payload.
 *
 * <h3>Out-of-order tolerance</h3>
 * All queries order results by {@code eventTimestamp ASC}. The balance query uses a
 * {@code SUM(CASE …)} expression so it is always mathematically correct regardless of
 * insertion order.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final TransactionEventRepository repository;

    /**
     * Per-eventId locks for concurrency-safe idempotency.
     * A lock is created on first contention and removed immediately after the
     * critical section completes, keeping memory usage bounded.
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public EventService(TransactionEventRepository repository) {
        this.repository = repository;
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * Submits a new transaction event.
     *
     * @return the newly persisted event
     * @throws DuplicateEventException if {@code eventId} already exists
     */
    @Transactional
    public TransactionEvent submitEvent(EventRequest request) {
        ReentrantLock lock = locks.computeIfAbsent(request.getEventId(), k -> new ReentrantLock());
        lock.lock();
        try {
            // Idempotency check — inside the lock to handle concurrent requests safely
            Optional<TransactionEvent> existing = repository.findById(request.getEventId());
            if (existing.isPresent()) {
                log.info("Duplicate submission for eventId={}", request.getEventId());
                throw new DuplicateEventException(request.getEventId(), existing.get());
            }

            TransactionEvent event = TransactionEvent.builder()
                    .eventId(request.getEventId())
                    .accountId(request.getAccountId())
                    .type(EventType.valueOf(request.getType()))
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .eventTimestamp(request.getEventTimestamp())
                    .receivedAt(Instant.now())
                    .metadata(request.getMetadata())
                    .build();

            TransactionEvent saved = repository.save(event);
            log.info("Event persisted: eventId={}, accountId={}, type={}, amount={}",
                    saved.getEventId(), saved.getAccountId(), saved.getType(), saved.getAmount());
            return saved;

        } finally {
            lock.unlock();
            locks.remove(request.getEventId(), lock);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TransactionEvent getEventById(String eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /**
     * Returns all events for an account ordered chronologically by eventTimestamp.
     * Always correct regardless of arrival order.
     */
    @Transactional(readOnly = true)
    public List<TransactionEvent> getEventsByAccount(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAsc(accountId);
    }

    /**
     * Returns a paginated slice of events for an account, ordered chronologically.
     */
    @Transactional(readOnly = true)
    public EventPageResponse getEventsByAccountPaginated(String accountId, int page, int size) {
        Page<TransactionEvent> pageResult =
                repository.findByAccountIdOrderByEventTimestampAsc(accountId, PageRequest.of(page, size));

        List<EventResponse> content = pageResult.getContent()
                .stream()
                .map(EventResponse::from)
                .toList();

        return EventPageResponse.builder()
                .events(content)
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .currentPage(pageResult.getNumber())
                .pageSize(pageResult.getSize())
                .last(pageResult.isLast())
                .build();
    }

    // ── Balance ───────────────────────────────────────────────────────────────

    /**
     * Computes {@code balance = sum(CREDIT) − sum(DEBIT)} for the given account.
     * Throws {@link AccountNotFoundException} if no events exist for the account.
     */
    @Transactional(readOnly = true)
    public BalanceResponse getAccountBalance(String accountId) {
        if (!repository.existsByAccountId(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        BigDecimal balance = repository.computeBalance(accountId);

        // Derive the currency from the first chronological event.
        // In a real system all events for one account share the same currency;
        // we keep this simple and deterministic.
        String currency = repository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(TransactionEvent::getCurrency)
                .findFirst()
                .orElse("USD");

        log.info("Balance for accountId={} is {}", accountId, balance);
        return BalanceResponse.builder()
                .accountId(accountId)
                .balance(balance)
                .currency(currency)
                .build();
    }
}
