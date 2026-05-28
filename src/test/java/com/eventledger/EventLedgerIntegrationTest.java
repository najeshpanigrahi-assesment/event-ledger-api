package com.eventledger;

import com.eventledger.dto.EventRequest;
import com.eventledger.repository.TransactionEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Event Ledger API — Integration Tests")
class EventLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionEventRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private EventRequest req(String eventId, String accountId, String type,
                              double amount, String timestamp) {
        return EventRequest.builder()
                .eventId(eventId)
                .accountId(accountId)
                .type(type)
                .amount(BigDecimal.valueOf(amount))
                .currency("USD")
                .eventTimestamp(Instant.parse(timestamp))
                .build();
    }

    private void submitEvent(EventRequest request) throws Exception {
        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    // =========================================================================
    // 1. POST /events — Submit Event
    // =========================================================================

    @Nested
    @DisplayName("POST /events — Submit Event")
    class SubmitEventTests {

        @Test
        @DisplayName("Creates a new event and returns HTTP 201 with full payload")
        void createEventReturns201() throws Exception {
            EventRequest request = req("evt-001", "acct-100", "CREDIT", 150.00, "2026-05-15T10:00:00Z");

            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.eventId").value("evt-001"))
                    .andExpect(jsonPath("$.accountId").value("acct-100"))
                    .andExpect(jsonPath("$.type").value("CREDIT"))
                    .andExpect(jsonPath("$.amount").value(150.00))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.eventTimestamp").exists())
                    .andExpect(jsonPath("$.receivedAt").exists());
        }

        @Test
        @DisplayName("Persists metadata and returns it in the response")
        void persistsMetadata() throws Exception {
            EventRequest request = EventRequest.builder()
                    .eventId("evt-meta")
                    .accountId("acct-200")
                    .type("DEBIT")
                    .amount(BigDecimal.valueOf(50.00))
                    .currency("USD")
                    .eventTimestamp(Instant.parse("2026-05-15T10:00:00Z"))
                    .metadata(Map.of("source", "mainframe-batch", "batchId", "B-9042"))
                    .build();

            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.metadata.source").value("mainframe-batch"))
                    .andExpect(jsonPath("$.metadata.batchId").value("B-9042"));
        }
    }

    // =========================================================================
    // 2. Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Idempotency — Duplicate Event Submission")
    class IdempotencyTests {

        @Test
        @DisplayName("Second submission with same eventId returns HTTP 200 with original event")
        void duplicateReturns200WithOriginalEvent() throws Exception {
            EventRequest request = req("evt-dup", "acct-300", "CREDIT", 200.00, "2026-05-15T10:00:00Z");

            // First submission → 201
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            // Second submission → 200 + original payload
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value("evt-dup"))
                    .andExpect(jsonPath("$.amount").value(200.00));

            // Only one record in DB
            assertEquals(1, repository.count());
        }

        @Test
        @DisplayName("Duplicate submissions do not alter the account balance")
        void duplicatesDoNotChangeBalance() throws Exception {
            EventRequest request = req("evt-bal-dup", "acct-400", "CREDIT", 500.00, "2026-05-15T10:00:00Z");

            submitEvent(request);
            submitEvent(request); // duplicate
            submitEvent(request); // duplicate again

            mockMvc.perform(get("/accounts/acct-400/balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(500.00));
        }

        @Test
        @DisplayName("10 concurrent requests for the same eventId: exactly one 201, nine 200s")
        void concurrentDuplicatesAreIdempotent() throws Exception {
            String eventId = "evt-concurrent-" + UUID.randomUUID();
            String body = objectMapper.writeValueAsString(
                    req(eventId, "acct-concurrent", "CREDIT", 100.00, "2026-05-15T10:00:00Z"));

            int threads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/events")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                    return result.getResponse().getStatus();
                }));
            }

            startLatch.countDown(); // release all threads simultaneously

            int created = 0, ok = 0;
            for (Future<Integer> f : futures) {
                int status = f.get();
                if (status == 201) created++;
                else if (status == 200) ok++;
            }
            executor.shutdown();

            assertEquals(1, created, "Exactly one thread should create the event");
            assertEquals(threads - 1, ok, "All other threads should get idempotent 200");
            assertEquals(1, repository.findByAccountIdOrderByEventTimestampAsc("acct-concurrent").size());
        }
    }

    // =========================================================================
    // 3. Out-of-Order Event Tolerance
    // =========================================================================

    @Nested
    @DisplayName("Out-of-Order Event Tolerance")
    class OutOfOrderTests {

        @Test
        @DisplayName("Events are always listed chronologically regardless of arrival order")
        void eventsListedChronologically() throws Exception {
            // Arrive in reverse order
            submitEvent(req("evt-t3", "acct-500", "CREDIT", 30.00, "2026-05-15T12:00:00Z"));
            submitEvent(req("evt-t1", "acct-500", "CREDIT", 10.00, "2026-05-15T10:00:00Z"));
            submitEvent(req("evt-t2", "acct-500", "CREDIT", 20.00, "2026-05-15T11:00:00Z"));

            mockMvc.perform(get("/events").param("account", "acct-500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0].eventId").value("evt-t1"))
                    .andExpect(jsonPath("$[1].eventId").value("evt-t2"))
                    .andExpect(jsonPath("$[2].eventId").value("evt-t3"));
        }

        @Test
        @DisplayName("Balance is correct regardless of event arrival order")
        void balanceCorrectWithOutOfOrderEvents() throws Exception {
            // DEBIT arrives before the CREDIT that preceded it chronologically
            submitEvent(req("evt-late",  "acct-600", "DEBIT",   75.00, "2026-05-15T12:00:00Z"));
            submitEvent(req("evt-early", "acct-600", "CREDIT", 200.00, "2026-05-15T09:00:00Z"));

            // 200 - 75 = 125
            mockMvc.perform(get("/accounts/acct-600/balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(125.00));
        }

        @Test
        @DisplayName("Ordering holds across events spanning multiple days")
        void orderingAcrossMultipleDays() throws Exception {
            submitEvent(req("evt-day3", "acct-700", "CREDIT", 300.00, "2026-05-17T08:00:00Z"));
            submitEvent(req("evt-day1", "acct-700", "CREDIT", 100.00, "2026-05-15T08:00:00Z"));
            submitEvent(req("evt-day2", "acct-700", "CREDIT", 200.00, "2026-05-16T08:00:00Z"));

            mockMvc.perform(get("/events").param("account", "acct-700"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventId").value("evt-day1"))
                    .andExpect(jsonPath("$[1].eventId").value("evt-day2"))
                    .andExpect(jsonPath("$[2].eventId").value("evt-day3"));
        }
    }

    // =========================================================================
    // 4. Balance Computation
    // =========================================================================

    @Nested
    @DisplayName("Balance Computation — GET /accounts/{accountId}/balance")
    class BalanceComputationTests {

        @Test
        @DisplayName("balance = sum(CREDITs) - sum(DEBITs)")
        void balanceCalculation() throws Exception {
            submitEvent(req("evt-c1", "acct-800", "CREDIT", 500.00, "2026-05-15T10:00:00Z"));
            submitEvent(req("evt-c2", "acct-800", "CREDIT", 250.00, "2026-05-15T11:00:00Z"));
            submitEvent(req("evt-d1", "acct-800", "DEBIT",  100.00, "2026-05-15T12:00:00Z"));
            submitEvent(req("evt-d2", "acct-800", "DEBIT",   50.00, "2026-05-15T13:00:00Z"));

            // 500 + 250 - 100 - 50 = 600
            mockMvc.perform(get("/accounts/acct-800/balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value("acct-800"))
                    .andExpect(jsonPath("$.balance").value(600.00))
                    .andExpect(jsonPath("$.currency").value("USD"));
        }

        @Test
        @DisplayName("Single CREDIT event — balance equals the amount")
        void singleCreditBalance() throws Exception {
            submitEvent(req("evt-single", "acct-810", "CREDIT", 999.99, "2026-05-15T10:00:00Z"));

            mockMvc.perform(get("/accounts/acct-810/balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(999.99));
        }

        @Test
        @DisplayName("Balance is zero when total CREDITs equal total DEBITs")
        void zeroBalance() throws Exception {
            submitEvent(req("evt-zero-c", "acct-820", "CREDIT", 100.00, "2026-05-15T10:00:00Z"));
            submitEvent(req("evt-zero-d", "acct-820", "DEBIT",  100.00, "2026-05-15T11:00:00Z"));

            mockMvc.perform(get("/accounts/acct-820/balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(0));
        }

        @Test
        @DisplayName("Returns HTTP 404 for an account that has no events")
        void unknownAccountReturns404() throws Exception {
            mockMvc.perform(get("/accounts/acct-unknown/balance"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(containsString("acct-unknown")));
        }
    }

    // =========================================================================
    // 5. GET /events/{id}
    // =========================================================================

    @Nested
    @DisplayName("GET /events/{id} — Retrieve Single Event")
    class GetEventByIdTests {

        @Test
        @DisplayName("Returns the event when it exists")
        void getExistingEvent() throws Exception {
            submitEvent(req("evt-get", "acct-900", "CREDIT", 75.00, "2026-05-15T10:00:00Z"));

            mockMvc.perform(get("/events/evt-get"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value("evt-get"))
                    .andExpect(jsonPath("$.amount").value(75.00));
        }

        @Test
        @DisplayName("Returns HTTP 404 when the event does not exist")
        void getNonExistentEvent() throws Exception {
            mockMvc.perform(get("/events/does-not-exist"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(containsString("does-not-exist")));
        }
    }

    // =========================================================================
    // 6. Input Validation
    // =========================================================================

    @Nested
    @DisplayName("Input Validation — Reject Invalid Payloads")
    class ValidationTests {

        @Test
        @DisplayName("Rejects request with missing eventId")
        void rejectMissingEventId() throws Exception {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    req(null, "acct-val", "CREDIT", 100.00, "2026-05-15T10:00:00Z"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.eventId").exists());
        }

        @Test
        @DisplayName("Rejects request with missing accountId")
        void rejectMissingAccountId() throws Exception {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    req("evt-v1", null, "CREDIT", 100.00, "2026-05-15T10:00:00Z"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.accountId").exists());
        }

        @Test
        @DisplayName("Rejects request with zero amount")
        void rejectZeroAmount() throws Exception {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    req("evt-v2", "acct-val", "CREDIT", 0.0, "2026-05-15T10:00:00Z"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.amount").value(containsString("greater than 0")));
        }

        @Test
        @DisplayName("Rejects request with negative amount")
        void rejectNegativeAmount() throws Exception {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    req("evt-v3", "acct-val", "CREDIT", -50.0, "2026-05-15T10:00:00Z"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.amount").exists());
        }

        @Test
        @DisplayName("Rejects request with unknown transaction type")
        void rejectUnknownType() throws Exception {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    req("evt-v4", "acct-val", "TRANSFER", 100.0, "2026-05-15T10:00:00Z"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.type").value(containsString("CREDIT or DEBIT")));
        }

        @Test
        @DisplayName("Rejects request with missing eventTimestamp")
        void rejectMissingTimestamp() throws Exception {
            EventRequest request = EventRequest.builder()
                    .eventId("evt-v5")
                    .accountId("acct-val")
                    .type("CREDIT")
                    .amount(BigDecimal.TEN)
                    .currency("USD")
                    .build(); // no eventTimestamp

            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.eventTimestamp").exists());
        }

        @Test
        @DisplayName("Rejects request with currency code longer than 3 characters")
        void rejectInvalidCurrency() throws Exception {
            EventRequest request = EventRequest.builder()
                    .eventId("evt-v6")
                    .accountId("acct-val")
                    .type("CREDIT")
                    .amount(BigDecimal.TEN)
                    .currency("USDD")
                    .eventTimestamp(Instant.parse("2026-05-15T10:00:00Z"))
                    .build();

            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.currency").exists());
        }

        @Test
        @DisplayName("Error response body contains error, timestamp, and fieldErrors map")
        void errorResponseIsWellFormed() throws Exception {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    req(null, null, "CREDIT", 0.0, "2026-05-15T10:00:00Z"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Validation Failed"))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.fieldErrors").isMap());
        }
    }

    // =========================================================================
    // 7. Pagination (Bonus)
    // =========================================================================

    @Nested
    @DisplayName("Pagination — GET /events?account=…&page=…&size=…")
    class PaginationTests {

        @Test
        @DisplayName("Returns correct page content ordered chronologically")
        void paginatedEventsAreChronological() throws Exception {
            for (int i = 1; i <= 5; i++) {
                submitEvent(req("evt-page-" + i, "acct-page", "CREDIT", i * 10.0,
                        "2026-05-15T" + String.format("%02d", i + 8) + ":00:00Z"));
            }

            mockMvc.perform(get("/events")
                            .param("account", "acct-page")
                            .param("page", "0")
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events", hasSize(2)))
                    .andExpect(jsonPath("$.events[0].eventId").value("evt-page-1"))
                    .andExpect(jsonPath("$.events[1].eventId").value("evt-page-2"))
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.currentPage").value(0))
                    .andExpect(jsonPath("$.last").value(false));
        }

        @Test
        @DisplayName("last=true on the final page")
        void lastPageFlagIsCorrect() throws Exception {
            for (int i = 1; i <= 3; i++) {
                submitEvent(req("evt-last-" + i, "acct-last", "CREDIT", 10.0,
                        "2026-05-15T" + String.format("%02d", i + 8) + ":00:00Z"));
            }

            mockMvc.perform(get("/events")
                            .param("account", "acct-last")
                            .param("page", "1")
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events", hasSize(1)))
                    .andExpect(jsonPath("$.last").value(true));
        }
    }
}
