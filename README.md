# Event Ledger

Two Spring Boot microservices that process financial transaction events (CREDIT / DEBIT) from upstream
systems that may deliver events **out of order** and **more than once**.

```
Client ──HTTP──▶  Event Gateway  (:8081, public)  ──REST (sync)──▶  Account Service (:8082, internal)
                  own H2 db: eventdb                                  own H2 db: accountdb
```

| | Event Gateway | Account Service |
|---|---|---|
| Role | validates, de-duplicates, stores events, calls Account Service | owns the ledger and balances |
| Endpoints | `POST /events`, `GET /events/{id}`, `GET /events?account=`, `GET /accounts/{id}/balance` (proxy), `GET /health` | `POST /accounts/{id}/transactions`, `GET /accounts/{id}/balance`, `GET /accounts/{id}`, `GET /health` |
| Database | in-memory H2 `eventdb` | in-memory H2 `accountdb` |

The services are independent processes with separate databases and no shared code: each has its own copy of
the HTTP contract (`event-gateway/.../client/Account*.java` vs `account-service/.../dto`).

## Prerequisites

* JDK 17+ and Maven 3.8+ (to build/run locally), **or** Docker with Compose.

## Run

**Docker Compose**

```bash
docker compose up --build
```

**Manually (two terminals)**

```bash
mvn -q -DskipTests install
cd account-service && mvn spring-boot:run     # terminal 1  -> :8082
cd event-gateway   && mvn spring-boot:run     # terminal 2  -> :8081
```

The Gateway finds the Account Service via `ACCOUNT_SERVICE_URL` (default `http://localhost:8082`).

## Try it

```bash
curl -i -X POST localhost:8081/events -H 'Content-Type: application/json' -d '{
  "eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":150.00,"currency":"USD",
  "eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"mainframe-batch","batchId":"B-9042"}}'
# 201 Created. Send it again -> 200 OK with the original event, balance unchanged.

curl 'localhost:8081/events?account=acct-123'        # chronological by eventTimestamp
curl localhost:8081/accounts/acct-123/balance        # {"accountId":"acct-123","balance":150.00,"currency":"USD"}
curl localhost:8081/health
curl localhost:8081/actuator/prometheus              # metrics (Prometheus format)
```

Status codes: `201` new event · `200` duplicate (original returned) · `400` validation · `404` unknown id ·
`409` same eventId with different content · `422` rejected by Account Service (e.g. currency mismatch) ·
`429` rate limit exceeded (`Retry-After` set) ·
`503` Account Service unavailable (event kept as `PENDING`, `Retry-After` header set).

**Rate limiting.** `POST /events` is limited per client address (default 50 requests/second, configurable under
`gateway.rate-limit.*` in `event-gateway/src/main/resources/application.yml`, can be disabled with
`gateway.rate-limit.enabled=false`). Excess requests are refused immediately with `429` before any database or
Account Service work. Reads and `/health` are not limited. The client key is the TCP peer address;
`X-Forwarded-For` is intentionally not trusted because clients control it.

## Tests

```bash
mvn test
```

| Where | What it proves |
|---|---|
| `account-service/.../AccountApiTest` | balance = Σcredits − Σdebits, idempotency, out-of-order, currency rule, validation, health |
| `event-gateway/.../EventApiTest` | validation, idempotent replay, 409 on conflicting replay, chronological listing, PENDING → retry → APPLIED, reads while Account is down |
| `event-gateway/.../ResilienceTest` | real HTTP against a stub that fails/hangs: bounded retries, circuit opens and **fails fast**, timeouts, 4xx doesn't trip the breaker, **`traceparent` reaches the Account Service** and incoming traces are continued |
| `integration-tests/.../FullFlowTest` | real Gateway + real Account Service over HTTP: out-of-order + duplicates + balances, trace id in **both services' logs**, outage → 503 → circuit open → reads still served |

## Design decisions

**Idempotency.** `eventId` has a UNIQUE constraint in both services, so even two racing identical requests cannot
both insert (the loser catches the constraint violation and answers with the winner's row). Neither service holds a
DB transaction open across a remote call.

**PENDING / APPLIED / REJECTED.** The Gateway stores the event *before* calling the Account Service. If the call
fails the event stays `PENDING` and the client gets `503`; retrying the same `eventId` is *not* a duplicate — it
re-drives the call. The Account Service is idempotent too, so a retry after a lost response can never double-apply.
A definitive `4xx` from the Account Service marks the event `REJECTED` and is never retried.

**Out-of-order.** The balance is never a running counter: it is `SUM(credit) − SUM(debit)` over the append-only
ledger, so arrival order cannot change it. Listings are `ORDER BY eventTimestamp` (ties broken by insertion id).
Each account has one currency (first transaction wins); a mismatching one is rejected rather than summed.

**Resiliency — retry + circuit breaker + timeouts** (Resilience4j, `AccountClient`):
* **Timeouts** (1 s connect, 2 s read): the Gateway never hangs on a slow Account Service.
* **Retry** (3 attempts, exponential backoff + jitter): absorbs blips. It is safe *because* the Account Service is
  idempotent. Only I/O errors, timeouts and 5xx are retried.
* **Circuit breaker** (opens at ≥50 % failures over 10 calls, 15 s open): if the Account Service is really down we stop
  hammering it and return `503` immediately instead of making every client wait out timeouts — protecting Gateway
  threads and giving the downstream room to recover. `4xx` responses are ignored by the breaker (caller's fault, not an outage).
* Order: `Retry( CircuitBreaker( call ) )` — each attempt is counted by the breaker, and once it is open
  `CallNotPermittedException` is not retried.

I chose these over a bulkhead because the realistic failure here is a *down or slow dependency* (needs fail-fast +
bounded waiting), and Tomcat's request threads already bound concurrency; a bulkhead would be the next addition if
more downstreams were added.

**Graceful degradation.** `GET /events/{id}` and `GET /events?account=` only touch the Gateway's DB and keep working;
`POST /events` and the balance proxy return `503` with a clear message. `/health` reports `DEGRADED` (still HTTP 200,
so an orchestrator does not restart a healthy Gateway) while the circuit is not closed.

## Observability

* **Tracing** — OpenTelemetry via Micrometer Tracing. The Gateway creates the trace (or continues an incoming W3C
  `traceparent`), `RestClient` propagates it to the Account Service, and both put `traceId`/`spanId` in every log
  line. Every response carries an `X-Trace-Id` header (also in error bodies) — grep it in both services' logs.
* **Logs** — JSON (ECS format, Spring Boot structured logging): `@timestamp`, `log.level`, `service.name`, `traceId`, `message`.
* **Health** — `GET /health` on both: status, service name, database connectivity (and circuit-breaker state on the Gateway).
* **Metrics** — `/actuator/prometheus` and `/actuator/metrics`: `gateway.events.submitted{outcome}`,
  `gateway.account.call{operation,outcome}` (latency timer), `account.transactions{type,outcome}`, plus Resilience4j
  circuit-breaker/retry metrics and standard HTTP/JVM metrics.

## Project layout

```
event-ledger/
├── pom.xml                  parent / aggregator
├── account-service/         :8082
├── event-gateway/           :8081
├── integration-tests/       boots both services in one JVM and tests the real flow
└── docker-compose.yml
```

H2 consoles (dev only): `http://localhost:8081/h2-console` (`jdbc:h2:mem:eventdb`) and
`http://localhost:8082/h2-console` (`jdbc:h2:mem:accountdb`), user `sa`, empty password.

## Possible next steps

OTLP export to Jaeger/Zipkin, contract tests (Pact), a bounded/expiring per-client limiter map, and an outbox/queue that
re-drives `PENDING` events automatically when the Account Service recovers.
