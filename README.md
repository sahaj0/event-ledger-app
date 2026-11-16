# Event Ledger

Two Spring Boot microservices that process financial transaction events (CREDIT / DEBIT) from upstream
systems that may deliver events **out of order** and **more than once**.
**Problem statement:** [Event Ledger take-home assignment (.docx)](docs/Java-AI-Assessment2.docx) — the document this repository solves.

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

---

## 1. Prerequisites

* **JDK 17+** (`java -version`) and **Maven 3.8+** (`mvn -v`) — or just **Docker** with Compose.
* **Postman** (optional) or **curl** to call the APIs.
* On Windows, `JAVA_HOME` must point to the JDK and `mvn` must be on the `PATH`. For example, in PowerShell:
  ```powershell
  $env:JAVA_HOME = "C:\path\to\jdk-17"
  $env:PATH = "C:\path\to\apache-maven\bin;$env:PATH"
  ```

## 2. Get the code and build

```bash
git clone https://github.com/sahaj0/event-ledger-app.git
cd event-ledger-app
mvn -DskipTests install
```

## 3. Start the services

Use **two terminals** (open both in the project root; on Windows set `JAVA_HOME`/`PATH` in each).

**Terminal 1 — Account Service (port 8082)**

```bash
cd account-service
mvn spring-boot:run
```

Wait for `Started AccountServiceApplication`.

**Terminal 2 — Event Gateway (port 8081)**

```bash
cd event-gateway
mvn spring-boot:run
```

Wait for `Started EventGatewayApplication`.

**Or with Docker instead** (from the project root; starts both, only the Gateway is published):

```bash
docker compose up --build
```

**Check both are up:**

```bash
curl http://localhost:8082/health
curl http://localhost:8081/health
```

Both should return `"status":"UP"`. The Gateway also shows `accountService.circuitBreaker: CLOSED`.

> By default data is **in memory**: restarting a service clears its data. Always restart **both** services for a clean slate.

**Optional: keep data across restarts.** Both services can use an embedded *file-based* H2 database instead.
Set these before starting them (the files land in a `data/` folder, which is git-ignored):

```bash
# macOS / Linux / Git Bash
ACCOUNT_DB_URL=jdbc:h2:file:./data/accountdb mvn spring-boot:run      # in account-service/
GATEWAY_DB_URL=jdbc:h2:file:./data/eventdb  mvn spring-boot:run      # in event-gateway/
```

```powershell
# Windows PowerShell
$env:ACCOUNT_DB_URL = "jdbc:h2:file:./data/accountdb"; mvn spring-boot:run     # in account-service
$env:GATEWAY_DB_URL = "jdbc:h2:file:./data/eventdb";  mvn spring-boot:run     # in event-gateway
```

`docker compose up` already does this with named volumes, so data survives container restarts;
`docker compose down -v` wipes it.

## 4. Test it end to end

Pick **one** way to call the API:

* **Postman:** import [`postman/event-ledger.postman_collection.json`](postman/event-ledger.postman_collection.json)
  (Import → select the file). It has the variables `gateway=http://localhost:8081` and `account=http://localhost:8082`
  and one request per step below, each with a status-code check. Run the folders in order, or use the steps below.
* **curl:** run the commands below from the project root (they read the payloads from [`samples/`](samples)).
  On Windows PowerShell use `curl.exe` instead of `curl` (plain `curl` is an alias of another command).

Ports: **8081 = Gateway** (events), **8082 = Account Service** (accounts). `POST /events` only exists on 8081.

### Step A — Health

| Request | Expected |
|---|---|
| `GET :8082/health` | `200`, `UP`, database `UP` |
| `GET :8081/health` | `200`, `UP`, circuit breaker `CLOSED` |

### Step B — Core flow through the Gateway

| # | Request | Expected |
|---|---|---|
| B1 | Create an event: `curl -i -X POST localhost:8081/events -H "Content-Type: application/json" -d @samples/evt-001.json` | `201`, `"status":"APPLIED"`, `Location` and `X-Trace-Id` headers |
| B2 | **Duplicate** — send B1 again | `200` with the original event; balance unchanged |
| B3 | **Conflict** — `-d @samples/evt-001-conflict.json` (same id, different amount) | `409` "already exists with different content" |
| B4 | **Out of order** — send the *latest* event first: `-d @samples/evt-003.json` | `201` |
| B5 | `-d @samples/evt-002.json` | `201` |
| B6 | `curl "localhost:8081/events?account=acct-123"` | `200`, events ordered by `eventTimestamp`: `evt-001, evt-002, evt-003` (not arrival order) |
| B7 | `curl localhost:8081/events/evt-003` | `200`; `.../events/nope` → `404` |
| B8 | `curl localhost:8081/accounts/acct-123/balance` | `balance` = **140.00** (150 + 20 − 30; the duplicate did not double count) |

### Step C — Account Service directly

| # | Request | Expected |
|---|---|---|
| C1 | `curl localhost:8082/accounts/acct-123/balance` | `140.00`, currency `USD` |
| C2 | `curl localhost:8082/accounts/acct-123` | balance, `"transactionCount":3`, recent transactions |
| C3 | `curl -i -X POST localhost:8082/accounts/acct-123/transactions -H "Content-Type: application/json" -d @samples/evt-001.json` | `200`, `"duplicate":true`, balance still 140.00 (the Account Service is idempotent on its own) |
| C4 | `... -d @samples/account-eur.json` (EUR on a USD account) | `422` "holds USD" |

### Step D — Validation (Gateway)

| Request | Expected |
|---|---|
| `-d "{}"` | `400` "Validation failed", lists every missing field |
| `-d @samples/invalid-amount.json` (amount 0; try `-5` too) | `400` "amount must be greater than 0" |
| `-d @samples/invalid-type.json` (`REFUND`) | `400` "type other than CREDIT/DEBIT" |
| `metadata` larger than 4000 characters (as JSON) | `400` "metadata is too large" |
| `curl localhost:8081/events` (no `account` parameter) | `400` "Required query parameter 'account' is missing" |

### Step E — Distributed tracing

1. Start each service with its output also saved to a file (run from the module folders):
   ```bash
   mvn spring-boot:run 2>&1 | tee account.log     # in account-service/
   mvn spring-boot:run 2>&1 | tee gateway.log     # in event-gateway/
   ```
   (`*.log` is git-ignored. On Windows PowerShell use `| Tee-Object -FilePath account.log`.)
2. Send any `POST /events` with a **new** `eventId` and copy the `X-Trace-Id` response header.
3. Search both logs for that id (`grep <traceId> gateway.log account.log`, or `Select-String` on Windows).
   You should see the Gateway's `Stored event … as PENDING` / `Event … APPLIED` **and** the Account Service's
   `Applied CREDIT of …` — all with the same `"traceId"`. That is one request traced across both services.
4. *Continue an existing trace:* add the request header
   `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`.
   The response `X-Trace-Id` is then `4bf92f3577b34da6a3ce929d0e0e4736`, and both logs contain it.

### Step F — Account Service outage (resilience + graceful degradation)

1. **Stop the Account Service** (`Ctrl+C` in terminal 1). Keep the Gateway running.
2. `POST` `samples/evt-100.json`, then `evt-101.json`, then `evt-102.json` to `localhost:8081/events`.
   * Each returns **`503`** with a `Retry-After: 15` header and a message that the event was *stored as PENDING*.
   * The first two take about a second (3 attempts with backoff); after about 5 failed calls the **circuit breaker
     opens** and the next ones fail in milliseconds.
3. `GET localhost:8081/health` → `200`, `"status":"DEGRADED"`, `circuitBreaker: OPEN` (still 200: the Gateway itself is healthy).
4. **Reads still work:** `GET localhost:8081/events/evt-100` → `200`, `"status":"PENDING"`;
   `GET localhost:8081/events?account=acct-123` → `200`.
5. `GET localhost:8081/accounts/acct-123/balance` → **`503`** "Account Service is unreachable".
6. **Recover:** start the Account Service again, wait for `Started AccountServiceApplication` **and at least 15 seconds**
   after the breaker opened (until then it still answers `503` instantly). Then re-send `evt-100`, `evt-101`, `evt-102`
   with the **same bodies** → each returns `200` and `"status":"APPLIED"`. A retry of a `PENDING` event is *not* rejected as
   a duplicate; it finishes the job. Health returns to `UP` / `CLOSED`.
7. **Or just wait — no client retry needed:** a background job in the Gateway re-sends events that have been `PENDING`
   for at least 30 s, every 15 s. About 45 s after the Account Service is back, `GET localhost:8081/events/evt-100`
   shows `"status":"APPLIED"` on its own, and the Gateway log shows `Re-drive applied N previously PENDING event(s)`.

> **Note:** with the default in-memory database, restarting the Account Service empties its ledger, so the balance after
> step 6 only reflects the transactions re-sent after the restart (e.g. 30.00 for three 10.00 credits), not the
> earlier ones, while the Gateway still lists those earlier events as `APPLIED`. Start both services with the
> file-based database (see section 3) to keep the ledger across the restart.

### Step G — Rate limiting

`POST /events` is limited to **50 requests per second per client** (`429` + `Retry-After` when exceeded). Reads and
`/health` are never limited.

* **Burst test (PowerShell):** sends 150 requests at once and counts the status codes.
  ```powershell
  Add-Type -AssemblyName System.Net.Http; [Net.ServicePointManager]::DefaultConnectionLimit = 50; $c = New-Object System.Net.Http.HttpClient; $tasks = 1..150 | % { $json = '{"eventId":"burst-' + [guid]::NewGuid() + '","accountId":"acct-burst","type":"CREDIT","amount":1,"currency":"USD","eventTimestamp":"2026-05-15T09:00:00Z"}'; $c.PostAsync("http://localhost:8081/events", (New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, "application/json"))) }; [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks); $tasks | % { [int]$_.Result.StatusCode } | Group-Object | Select Count, Name
  ```
  Expect a mix of `201` and `429` (the exact split varies with speed; roughly 50 per second get through).
* **Hand test in Postman/curl:** temporarily set `gateway.rate-limit.limit-for-period: 3` and
  `refresh-period: 1m` in `event-gateway/src/main/resources/application.yml`, restart the Gateway, then send 4 new
  events: the 4th returns `429` with `Retry-After: 60`. Revert the file afterwards.

### Step H — Metrics

* Gateway: `GET localhost:8081/actuator/prometheus` — look for `gateway_events_submitted_total{outcome=...}`
  (`applied`, `duplicate`, `conflict`, `unavailable`), `gateway_account_call_seconds_count`,
  `gateway_ratelimit_rejected_total`, `resilience4j_circuitbreaker_state`.
* Account Service: `GET localhost:8082/actuator/prometheus` — `account_transactions_total{type,outcome}`.
* A single metric as JSON: `GET localhost:8081/actuator/metrics/gateway.events.submitted`.

After only Steps A–D on a fresh start, the Gateway shows `applied` = 3, `duplicate` = 1, `conflict` = 1
(validation errors are rejected before they are counted).

### Dev-only database consoles

`http://localhost:8081/h2-console` (JDBC URL `jdbc:h2:mem:eventdb`) and `http://localhost:8082/h2-console`
(`jdbc:h2:mem:accountdb`); user `sa`, empty password. These only work while the service is running.

## 5. Automated tests

```bash
mvn test
```

| Where | What it proves |
|---|---|
| `account-service/.../AccountApiTest` | balance = Σcredits − Σdebits, idempotency, **20 identical transactions racing at once → applied exactly once**, out-of-order, currency rule, validation, health |
| `event-gateway/.../EventApiTest` | validation (incl. oversized `metadata` → 400), idempotent replay, **20 identical requests racing at once → exactly one 201, the rest 200**, 409 on conflicting replay, chronological listing, PENDING → retry → APPLIED, reads while the Account Service is down |
| `event-gateway/.../RedriveTest` | automatic re-drive: PENDING events are applied once the Account Service recovers, it stops at the first outage, a rejected event becomes `REJECTED` without blocking others, fresh events are left to the client's retry |
| `event-gateway/.../ResilienceTest` | real HTTP against a stub that fails/hangs: bounded retries, circuit opens and **fails fast**, timeouts, 4xx does not trip the breaker, **`traceparent` reaches the Account Service**, incoming traces are continued |
| `event-gateway/.../RateLimitTest` | the 4th request over a limit of 3 gets `429` + `Retry-After`; reads and health are not limited |
| `integration-tests/.../FullFlowTest` | real Gateway + real Account Service over HTTP: out-of-order + duplicates + balances, trace id in **both services' logs**, outage → 503 → circuit open → reads still served |

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Port 8081 (or 8082) was already in use` | Another copy is running. Stop it, or find the process: `netstat -ano \| findstr :8081` (Windows). |
| `POST http://localhost:8082/events` → `404` | Wrong service. `/events` lives on the **Gateway (8081)**. |
| Maven: `PKIX path building failed` | Java does not trust your network's TLS inspection certificate. Build on a network without it, or import your organisation's CA into the JDK (`keytool -importcert -cacerts`). Do not disable SSL checks. |
| `mvn` / `JAVA_HOME is not defined correctly` | Set `JAVA_HOME` to a JDK 17 and put Maven's `bin` on the `PATH` (see section 1). |
| `503` right after the Account Service is back | The circuit breaker stays open for 15 s; wait, then retry the same `eventId`. |
| Balance is lower than expected after restarting the Account Service | Its in-memory database was wiped (see the note in Step F). Use the file-based database (section 3). |
| `Database may be already in use` | Two copies are using the same `data/*.mv.db` file. Stop the other copy first. |

## 7. Design decisions

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

**Rate limiting.** `POST /events` is limited per client address (default 50 requests/second, configurable under
`gateway.rate-limit.*` in `event-gateway/src/main/resources/application.yml`, can be disabled with
`gateway.rate-limit.enabled=false`). Excess requests are refused immediately with `429` before any database or
Account Service work. The client key is the TCP peer address; `X-Forwarded-For` is intentionally not trusted because
clients control it.

**Automatic re-drive of PENDING events.** `PendingEventRedriver` runs every 15 s and re-sends events that have been
`PENDING` for at least 30 s (oldest first, 100 per run; `gateway.redrive.*` in `application.yml`, off with
`gateway.redrive.enabled=false`). The 30 s minimum keeps it from racing a client's own retry of a fresh event; if both
do send, the Account Service's idempotency makes it harmless. It uses the same retry + circuit breaker, stops at the
first outage, and is a no-op while the breaker is open. This closes the gap where events stayed `PENDING` until a
client happened to retry.

**Graceful degradation.** `GET /events/{id}` and `GET /events?account=` only touch the Gateway's DB and keep working;
`POST /events` and the balance proxy return `503` with a clear message. `/health` reports `DEGRADED` (still HTTP 200,
so an orchestrator does not restart a healthy Gateway) while the circuit is not closed.

Status codes: `201` new event · `200` duplicate (original returned) · `400` validation · `404` unknown id ·
`409` same eventId with different content · `422` rejected by Account Service (e.g. currency mismatch) ·
`429` rate limit exceeded (`Retry-After` set) · `503` Account Service unavailable (event kept as `PENDING`, `Retry-After` set).

## 8. Observability

* **Tracing** — OpenTelemetry via Micrometer Tracing. The Gateway creates the trace (or continues an incoming W3C
  `traceparent`), `RestClient` propagates it to the Account Service, and both put `traceId`/`spanId` in every log
  line. Every response carries an `X-Trace-Id` header (also in error bodies).
* **Logs** — JSON (ECS format, Spring Boot structured logging): `@timestamp`, `log.level`, `service.name`, `traceId`, `message`.
* **Health** — `GET /health` on both: status, service name, database connectivity (and circuit-breaker state on the Gateway).
* **Metrics** — Prometheus/`/actuator/metrics`: `gateway.events.submitted{outcome}`, `gateway.account.call{operation,outcome}`
  (latency timer), `gateway.ratelimit.rejected`, `gateway.events.redriven{outcome}`, `account.transactions{type,outcome}`, plus Resilience4j and standard HTTP/JVM metrics.

## 9. Project layout

```
event-ledger-app/
├── pom.xml                  parent / aggregator
├── account-service/         :8082
├── event-gateway/           :8081
├── integration-tests/       boots both services in one JVM and tests the real flow
├── docs/                    problem statement (original assignment, .docx)
├── postman/                 importable Postman collection
├── samples/                 example request bodies used by the curl commands above
└── docker-compose.yml
```

## 10. Possible next steps

OTLP export to Jaeger/Zipkin, contract tests (Pact), an external database (Postgres) instead of embedded H2,
paging on `GET /events`, stricter timestamp/currency validation, and a bounded/expiring per-client limiter map.
