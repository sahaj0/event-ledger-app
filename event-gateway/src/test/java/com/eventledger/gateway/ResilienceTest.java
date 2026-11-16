package com.eventledger.gateway;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Gateway (real HTTP server, real RestClient, real Resilience4j) against a stub Account Service
 * that we can make fail, hang or answer with 4xx. Also verifies W3C trace propagation on the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability   // @SpringBootTest switches tracing off by default; we are testing it
class ResilienceTest {

    // ---- stub Account Service ------------------------------------------------------------
    record Reply(int status, String body, long delayMillis) {
    }

    static final AtomicReference<Reply> REPLY = new AtomicReference<>();
    static final AtomicInteger HITS = new AtomicInteger();
    static final AtomicInteger FAIL_FIRST = new AtomicInteger();
    static final List<String> TRACEPARENTS = new CopyOnWriteArrayList<>();
    static final HttpServer STUB;

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        STUB.createContext("/", exchange -> {
            int hit = HITS.incrementAndGet();
            String tp = exchange.getRequestHeaders().getFirst("traceparent");
            if (tp != null) {
                TRACEPARENTS.add(tp);
            }
            exchange.getRequestBody().readAllBytes();
            Reply r = hit <= FAIL_FIRST.get() ? new Reply(500, "{}", 0) : REPLY.get();
            try {
                Thread.sleep(r.delayMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(r.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException clientGaveUp) {
                // read timeout on the Gateway side: expected in the slow test
            } finally {
                exchange.close();
            }
        });
        // concurrent handlers: a slow reply must not queue up later requests behind it
        STUB.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        STUB.start();
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("account.service.url", () -> "http://localhost:" + STUB.getAddress().getPort());
        r.add("account.service.read-timeout", () -> "400ms");
        // small, fast breaker: opens after 4 failed calls
        r.add("resilience4j.circuitbreaker.instances.account.sliding-window-size", () -> "4");
        r.add("resilience4j.circuitbreaker.instances.account.minimum-number-of-calls", () -> "4");
        r.add("resilience4j.circuitbreaker.instances.account.wait-duration-in-open-state", () -> "60s");
        r.add("resilience4j.retry.instances.account.max-attempts", () -> "2");
        r.add("resilience4j.retry.instances.account.wait-duration", () -> "10ms");
    }

    @LocalServerPort
    int port;

    @Autowired
    CircuitBreakerRegistry breakers;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        breakers.circuitBreaker("account").reset();
        HITS.set(0);
        FAIL_FIRST.set(0);
        TRACEPARENTS.clear();
        REPLY.set(ok());
    }

    private static Reply ok() {
        return new Reply(201, "{\"balance\":10.00,\"duplicate\":false}", 0);
    }

    private HttpResponse<String> post(String eventId, String... headers) throws Exception {
        String body = """
                {"eventId":"%s","accountId":"acct-r","type":"CREDIT","amount":10,"currency":"USD",
                 "eventTimestamp":"2026-05-15T14:02:11Z"}""".formatted(eventId);
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    // ---- resiliency -----------------------------------------------------------------------

    @Test
    void retriesTransientFailureThenSucceeds() throws Exception {
        FAIL_FIRST.set(1);                        // first attempt 500, second attempt OK
        var res = post(id());
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(HITS.get()).isEqualTo(2);
    }

    @Test
    void accountFailureGives503NotA500_andRetriesAreBounded() throws Exception {
        REPLY.set(new Reply(500, "{}", 0));
        var res = post(id());
        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.headers().firstValue("Retry-After")).isPresent();
        assertThat(res.body()).contains("PENDING");
        assertThat(HITS.get()).isEqualTo(2);     // max-attempts=2: no endless retrying
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailures_thenFailsFastWithoutCallingAccount() throws Exception {
        REPLY.set(new Reply(500, "{}", 0));
        post(id());
        post(id());                               // 2 events x 2 attempts = 4 failed calls -> OPEN
        CircuitBreaker cb = breakers.circuitBreaker("account");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int hitsWhenOpened = HITS.get();
        long start = System.nanoTime();
        var res = post(id());
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(HITS.get()).as("no call must reach the Account Service while OPEN").isEqualTo(hitsWhenOpened);
        assertThat(ms).as("fail fast").isLessThan(300);

        // health reflects it, and the Gateway is still serving
        var health = get("/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"DEGRADED\"").contains("\"circuitBreaker\":\"OPEN\"");
    }

    @Test
    void readsKeepWorkingWhileCircuitIsOpen() throws Exception {
        REPLY.set(new Reply(500, "{}", 0));
        String e = id();
        post(e);
        post(id());
        assertThat(breakers.circuitBreaker("account").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(get("/events/" + e).statusCode()).isEqualTo(200);
        assertThat(get("/events/" + e).body()).contains("\"status\":\"PENDING\"");
        assertThat(get("/events?account=acct-r").statusCode()).isEqualTo(200);
        var balance = get("/accounts/acct-r/balance");
        assertThat(balance.statusCode()).isEqualTo(503);
        assertThat(balance.body()).contains("unreachable");
    }

    @Test
    void slowAccountServiceTimesOutInsteadOfHanging() throws Exception {
        assertThat(post(id()).statusCode()).isEqualTo(201);   // warm-up so we time the timeout, not class loading
        HITS.set(0);
        REPLY.set(new Reply(201, "{}", 3000));    // far slower than the 400ms read timeout
        long start = System.nanoTime();
        var res = post(id());
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(ms).as("2 attempts x ~400ms, never the 3s the stub wanted").isLessThan(2000);
    }

    @Test
    void clientErrorsFromAccountDoNotTripTheBreaker() throws Exception {
        REPLY.set(new Reply(422, "{\"message\":\"Account holds EUR\"}", 0));
        for (int i = 0; i < 6; i++) {
            var res = post(id());
            assertThat(res.statusCode()).as(res.body()).isEqualTo(422);
            assertThat(res.body()).contains("Account holds EUR");
            assertThat(HITS.get()).as("4xx is not retried (after request %d)", i + 1).isEqualTo(i + 1);
        }
        assertThat(breakers.circuitBreaker("account").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(HITS.get()).as("4xx is not retried").isEqualTo(6);
    }

    // ---- tracing --------------------------------------------------------------------------

    @Test
    void gatewayGeneratesTraceIdAndPropagatesItToAccountService() throws Exception {
        var res = post(id());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        String traceId = res.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(TRACEPARENTS).hasSize(1);
        // W3C traceparent: version-traceId-parentSpanId-flags
        assertThat(TRACEPARENTS.get(0).split("-")[1]).isEqualTo(traceId);
    }

    @Test
    void incomingTraceIsContinuedNotReplaced() throws Exception {
        String upstreamTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
        var res = post(id(), "traceparent", "00-" + upstreamTrace + "-00f067aa0ba902b7-01");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        assertThat(res.headers().firstValue("X-Trace-Id")).hasValue(upstreamTrace);
        assertThat(TRACEPARENTS.get(0).split("-")[1]).isEqualTo(upstreamTrace);
    }
}
