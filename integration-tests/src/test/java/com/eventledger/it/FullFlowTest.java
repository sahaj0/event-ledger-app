package com.eventledger.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eventledger.account.AccountServiceApplication;
import com.eventledger.gateway.EventGatewayApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the REAL Account Service and the REAL Gateway (two Spring contexts, two H2 databases, two
 * embedded Tomcats on random ports, talking plain HTTP) and drives the whole system through the
 * Gateway's public API.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullFlowTest {

    static ConfigurableApplicationContext account;
    static ConfigurableApplicationContext gateway;
    static String gatewayUrl;
    static String accountUrl;
    static ListAppender<ILoggingEvent> logs;

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void startBothServices() {
        account = new SpringApplicationBuilder(AccountServiceApplication.class)
                .run("--spring.config.name=it-account", "--server.port=0");
        accountUrl = "http://localhost:" + account.getEnvironment().getProperty("local.server.port");

        gateway = new SpringApplicationBuilder(EventGatewayApplication.class)
                .run("--spring.config.name=it-gateway", "--server.port=0", "--account.service.url=" + accountUrl);
        gatewayUrl = "http://localhost:" + gateway.getEnvironment().getProperty("local.server.port");

        // capture every log event (with its MDC) so we can assert on trace ids in BOTH services' logs
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterAll
    static void stopBoth() {
        if (logs != null) {
            ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        }
        if (gateway != null) gateway.close();
        if (account != null && account.isActive()) account.close();
    }

    // ---- helpers --------------------------------------------------------------------------

    record Res(int status, JsonNode body, String traceId) {
    }

    static Res send(String method, String url, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isBlank() ? null : JSON.readTree(r.body()),
                r.headers().firstValue("X-Trace-Id").orElse(null));
    }

    static Res submit(String eventId, String acct, String type, String amount, String ts, String... headers)
            throws Exception {
        return send("POST", gatewayUrl + "/events", """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, acct, type, amount, ts), headers);
    }

    static String uid() {
        return UUID.randomUUID().toString();
    }

    // ---- scenarios ------------------------------------------------------------------------

    @Test
    @Order(1)
    void fullFlow_outOfOrder_duplicates_andBalanceAcrossBothServices() throws Exception {
        String a = "acct-" + uid();
        // out of order: the latest event arrives first
        assertThat(submit(a + "-3", a, "DEBIT", "30.00", "2026-05-15T18:00:00Z").status()).isEqualTo(201);
        assertThat(submit(a + "-1", a, "CREDIT", "150.00", "2026-05-15T09:00:00Z").status()).isEqualTo(201);
        assertThat(submit(a + "-2", a, "CREDIT", "20.00", "2026-05-15T12:00:00Z").status()).isEqualTo(201);
        // the same event delivered twice more
        assertThat(submit(a + "-1", a, "CREDIT", "150.00", "2026-05-15T09:00:00Z").status()).isEqualTo(200);
        assertThat(submit(a + "-3", a, "DEBIT", "30.00", "2026-05-15T18:00:00Z").status()).isEqualTo(200);

        // Gateway listing is chronological
        Res list = send("GET", gatewayUrl + "/events?account=" + a, null);
        assertThat(list.body()).hasSize(3);
        assertThat(list.body().findValuesAsText("eventId")).containsExactly(a + "-1", a + "-2", a + "-3");
        assertThat(list.body().findValuesAsText("status")).containsOnly("APPLIED");

        // balance = 150 + 20 - 30 = 140, no double-counting, seen through the Gateway ...
        Res viaGateway = send("GET", gatewayUrl + "/accounts/" + a + "/balance", null);
        assertThat(viaGateway.body().get("balance").decimalValue()).isEqualByComparingTo("140");
        // ... and straight from the Account Service, which holds exactly 3 transactions
        Res direct = send("GET", accountUrl + "/accounts/" + a, null);
        assertThat(direct.body().get("balance").decimalValue()).isEqualByComparingTo("140");
        assertThat(direct.body().get("transactionCount").asInt()).isEqualTo(3);
    }

    @Test
    @Order(2)
    void accountServiceItselfIsIdempotent_evenIfGatewayReplaysTheCall() throws Exception {
        String a = "acct-" + uid();
        String tx = """
                {"eventId":"%s-1","type":"CREDIT","amount":10,"currency":"USD","eventTimestamp":"2026-05-15T09:00:00Z"}
                """.formatted(a);
        assertThat(send("POST", accountUrl + "/accounts/" + a + "/transactions", tx).status()).isEqualTo(201);
        Res again = send("POST", accountUrl + "/accounts/" + a + "/transactions", tx);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("duplicate").asBoolean()).isTrue();
        assertThat(again.body().get("balance").decimalValue()).isEqualByComparingTo("10");
    }

    @Test
    @Order(3)
    void traceIdFlowsFromGatewayIntoBothServicesLogs() throws Exception {
        String a = "acct-" + uid();
        Res res = submit(a + "-1", a, "CREDIT", "5", "2026-05-15T09:00:00Z");
        assertThat(res.status()).isEqualTo(201);
        String traceId = res.traceId();
        assertThat(traceId).matches("[0-9a-f]{32}");

        List<ILoggingEvent> forTrace = List.copyOf(logs.list).stream()
                .filter(e -> traceId.equals(e.getMDCPropertyMap().get("traceId"))).toList();
        assertThat(forTrace).as("Gateway logged this trace id")
                .anyMatch(e -> e.getLoggerName().startsWith("com.eventledger.gateway"));
        assertThat(forTrace).as("Account Service logged the SAME trace id")
                .anyMatch(e -> e.getLoggerName().startsWith("com.eventledger.account"));

        // and the Account Service answered under that trace too: query it with the id we were given
        Res direct = send("GET", accountUrl + "/accounts/" + a + "/balance", null,
                "traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");
        assertThat(direct.traceId()).isEqualTo(traceId);
    }

    @Test
    @Order(4)
    void accountOutage_gives503_circuitOpens_readsKeepWorking_balanceExplainsItself() throws Exception {
        String a = "acct-" + uid();
        assertThat(submit(a + "-ok", a, "CREDIT", "10", "2026-05-15T09:00:00Z").status()).isEqualTo(201);

        account.close();   // the Account Service process goes away

        Res first = submit(a + "-down1", a, "CREDIT", "10", "2026-05-15T10:00:00Z");
        assertThat(first.status()).isEqualTo(503);
        assertThat(first.body().get("message").asText()).contains("PENDING");
        submit(a + "-down2", a, "CREDIT", "10", "2026-05-15T11:00:00Z");   // enough failed calls to open the breaker

        long start = System.nanoTime();
        Res failFast = submit(a + "-down3", a, "CREDIT", "10", "2026-05-15T12:00:00Z");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(failFast.status()).isEqualTo(503);
        assertThat(ms).as("circuit is open: fail fast").isLessThan(300);

        Res health = send("GET", gatewayUrl + "/health", null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body().get("status").asText()).isEqualTo("DEGRADED");
        assertThat(health.body().at("/checks/accountService/circuitBreaker").asText()).isEqualTo("OPEN");

        // reads served from the Gateway's own database
        Res one = send("GET", gatewayUrl + "/events/" + a + "-down1", null);
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.body().get("status").asText()).isEqualTo("PENDING");
        Res list = send("GET", gatewayUrl + "/events?account=" + a, null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(4);

        Res balance = send("GET", gatewayUrl + "/accounts/" + a + "/balance", null);
        assertThat(balance.status()).isEqualTo(503);
        assertThat(balance.body().get("message").asText()).contains("unreachable");
    }
}

