# Event Ledger API

A production-quality RESTful API for processing financial transaction events from multiple upstream systems.

---

## Features

| Feature | Details |
|---|---|
| **Idempotency** | Re-submitting the same `eventId` returns the original event (HTTP 200), never a duplicate |
| **Out-of-order tolerance** | Events are always listed and balanced in `eventTimestamp` order regardless of arrival order |
| **Concurrency-safe** | Simultaneous POSTs for the same `eventId` are serialised — exactly one insert, rest are idempotent |
| **Balance computation** | `balance = Σ(CREDIT) − Σ(DEBIT)`, always correct |
| **Pagination** | Optional `page` and `size` parameters on the event listing endpoint |
| **Swagger UI** | Interactive API docs at `/swagger-ui.html` |
| **H2 console** | In-browser DB inspection at `/h2-console` |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.2.5 |
| Database | H2 (in-memory, zero setup) |
| Build | Maven 3.8+ |
| API Docs | SpringDoc OpenAPI 2 (Swagger UI) |

---

## Prerequisites

- **Java 21+** → https://adoptium.net/
- **Maven 3.8+** → https://maven.apache.org/download.cgi

```bash
java -version   # must show 21+
mvn  -version   # must show 3.8+
```

---

## Setup

```bash
git clone https://github.com/YOUR_USERNAME/event-ledger-api.git
cd event-ledger-api
mvn dependency:resolve -q
```

---

## Run the Application

```bash
mvn spring-boot:run
```

The server starts on **http://localhost:8080**.  
The H2 in-memory database is created automatically on startup — no configuration needed.

---

## Run the Tests

```bash
mvn test
```

All 20 tests should pass. Output:

```
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## API Reference

### `POST /events` — Submit a transaction event

**Idempotent**: submitting the same `eventId` multiple times is safe.

```http
POST /events
Content-Type: application/json

{
  "eventId":        "evt-001",
  "accountId":      "acct-123",
  "type":           "CREDIT",
  "amount":         150.00,
  "currency":       "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {
    "source":  "mainframe-batch",
    "batchId": "B-9042"
  }
}
```

| Status | Meaning |
|--------|---------|
| `201` | Event created |
| `200` | Duplicate — original event returned |
| `400` | Validation error — see `fieldErrors` in response body |

---

### `GET /events/{id}` — Retrieve a single event

```http
GET /events/evt-001
```

| Status | Meaning |
|--------|---------|
| `200` | Event found |
| `404` | Event not found |

---

### `GET /events?account={accountId}` — List events for an account

Always returns events ordered by `eventTimestamp ASC` regardless of arrival order.

```http
GET /events?account=acct-123
```

**Optional pagination:**

```http
GET /events?account=acct-123&page=0&size=20
```

Without `page`/`size`: returns a flat JSON array.  
With `page`/`size`: returns a paginated wrapper:

```json
{
  "events":        [ ... ],
  "totalElements": 42,
  "totalPages":    3,
  "currentPage":   0,
  "pageSize":      20,
  "last":          false
}
```

---

### `GET /accounts/{accountId}/balance` — Get account balance

```http
GET /accounts/acct-123/balance
```

Response:

```json
{
  "accountId": "acct-123",
  "balance":   425.00,
  "currency":  "USD"
}
```

`balance = Σ(CREDIT amounts) − Σ(DEBIT amounts)`

| Status | Meaning |
|--------|---------|
| `200` | Balance returned |
| `404` | Account has no events |

---

## Design Decisions

### Idempotency
`eventId` is the JPA `@Id` (natural primary key). A duplicate insert is caught _before_ hitting the DB by the in-memory lock check, so the original record is returned cleanly without an exception propagating.

### Concurrency
A `ConcurrentHashMap<String, ReentrantLock>` provides per-`eventId` locking:

1. Thread A and Thread B both arrive for `eventId = "evt-001"`.
2. One acquires the lock, checks the DB — nothing found — inserts.
3. The other acquires the lock, checks the DB — record found — throws `DuplicateEventException`.
4. The exception handler returns the original event with HTTP 200.
5. The lock is released and removed to keep the map bounded.

### Out-of-order Tolerance
All list queries use `ORDER BY eventTimestamp ASC`. The balance query uses a single SQL `SUM(CASE WHEN type = CREDIT THEN amount ELSE -amount END)` so insertion order is irrelevant.

### No Lombok
All DTOs and entities use hand-written getters, setters, and builders. This eliminates IDE annotation-processor setup and makes the code straightforward to read without tooling.

### Clean Architecture
```
com.eventledger
├── EventLedgerApplication.java
├── config/          OpenApiConfig
├── controller/      EventController
├── dto/             EventRequest, EventResponse, BalanceResponse,
│                    EventPageResponse, ErrorResponse
├── enums/           EventType
├── exception/       AccountNotFoundException, DuplicateEventException,
│                    EventNotFoundException, GlobalExceptionHandler
├── model/           TransactionEvent, JsonMetadataConverter
├── repository/      TransactionEventRepository
└── service/         EventService
```

---

## Developer Tools (while running)

| URL | Description |
|-----|-------------|
| http://localhost:8080/swagger-ui.html | Interactive Swagger UI |
| http://localhost:8080/api-docs | Raw OpenAPI JSON |
| http://localhost:8080/h2-console | H2 browser console |

H2 console settings: JDBC URL `jdbc:h2:mem:eventledgerdb`, Username `sa`, Password _(empty)_.

---

## Quick Smoke Test (curl)

```bash
# 1. Submit a CREDIT
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":500,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}'

# 2. Submit an out-of-order DEBIT (earlier timestamp, later arrival)
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-002","accountId":"acct-123","type":"DEBIT","amount":75,"currency":"USD","eventTimestamp":"2026-05-14T08:00:00Z"}'

# 3. Balance = 500 - 75 = 425
curl -s http://localhost:8080/accounts/acct-123/balance

# 4. List — evt-002 appears first (chronological order)
curl -s "http://localhost:8080/events?account=acct-123"

# 5. Duplicate is safe — returns original with HTTP 200
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":500,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}'
# → 200
```
