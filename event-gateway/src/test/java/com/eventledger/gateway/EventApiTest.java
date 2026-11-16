package com.eventledger.gateway;

import com.eventledger.gateway.client.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Gateway behaviour with the Account Service client mocked out (no network, no resilience layer). */
@SpringBootTest
@AutoConfigureMockMvc
class EventApiTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountClient accountClient;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String json(String eventId, String account, String type, String amount, String ts) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,"currency":"USD",
                 "eventTimestamp":"%s","metadata":{"source":"test","batchId":"B-1"}}
                """.formatted(eventId, account, type, amount, ts);
    }

    private MockHttpServletRequestBuilder submit(String body) {
        return post("/events").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void accountAccepts() {
        when(accountClient.applyTransaction(any(), any()))
                .thenReturn(new AccountTransactionResponse(BigDecimal.TEN, false));
    }

    // ---- core functionality ---------------------------------------------------------------

    @Test
    void newEventIsStoredAppliedAndReturned201() throws Exception {
        accountAccepts();
        String e = id(), a = id();
        mvc.perform(submit(json(e, a, "CREDIT", "150.00", "2026-05-15T14:02:11Z")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/events/" + e)))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.metadata.batchId").value("B-1"));
        mvc.perform(get("/events/{id}", e)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(e));
        verify(accountClient).applyTransaction(eq(a), argThat(r -> r.eventId().equals(e)
                && r.type().equals("CREDIT") && r.amount().compareTo(new BigDecimal("150")) == 0));
    }

    @Test
    void duplicateEventIsIdempotent_returnsOriginalWith200_andDoesNotCallAccountAgain() throws Exception {
        accountAccepts();
        String e = id(), a = id();
        String body = json(e, a, "CREDIT", "50", "2026-05-15T14:02:11Z");
        mvc.perform(submit(body)).andExpect(status().isCreated());
        mvc.perform(submit(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(e))
                .andExpect(jsonPath("$.status").value("APPLIED"));
        verify(accountClient, times(1)).applyTransaction(any(), any());
        mvc.perform(get("/events").param("account", a)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void sameEventIdWithDifferentContentIsA409() throws Exception {
        accountAccepts();
        String e = id(), a = id();
        mvc.perform(submit(json(e, a, "CREDIT", "50", "2026-05-15T14:02:11Z"))).andExpect(status().isCreated());
        mvc.perform(submit(json(e, a, "CREDIT", "51", "2026-05-15T14:02:11Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("different content")));
    }

    @Test
    void eventsAreListedByEventTimestampNotArrivalOrder() throws Exception {
        accountAccepts();
        String a = id();
        mvc.perform(submit(json("late-" + a, a, "DEBIT", "5", "2026-05-15T18:00:00Z"))).andExpect(status().isCreated());
        mvc.perform(submit(json("early-" + a, a, "CREDIT", "9", "2026-05-15T08:00:00Z"))).andExpect(status().isCreated());
        mvc.perform(submit(json("mid-" + a, a, "CREDIT", "1", "2026-05-15T12:00:00Z"))).andExpect(status().isCreated());
        mvc.perform(get("/events").param("account", a))
                .andExpect(jsonPath("$[*].eventId", contains("early-" + a, "mid-" + a, "late-" + a)));
    }

    @Test
    void getUnknownEventIs404() throws Exception {
        mvc.perform(get("/events/{id}", "nope-" + id())).andExpect(status().isNotFound());
    }

    @Test
    void listWithoutAccountParamIs400() throws Exception {
        mvc.perform(get("/events")).andExpect(status().isBadRequest());
    }

    // ---- validation -----------------------------------------------------------------------

    @Test
    void missingRequiredFieldsAreRejectedWithFieldMessages() throws Exception {
        mvc.perform(submit("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItems(
                        containsString("eventId"), containsString("accountId"), containsString("type"),
                        containsString("amount"), containsString("currency"), containsString("eventTimestamp"))));
        verifyNoInteractions(accountClient);
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() throws Exception {
        for (String amount : new String[]{"0", "-10.5"}) {
            mvc.perform(submit(json(id(), "a", "CREDIT", amount, "2026-05-15T14:02:11Z")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0]", containsString("amount")));
        }
        verifyNoInteractions(accountClient);
    }

    @Test
    void unknownTypeIsRejected() throws Exception {
        mvc.perform(submit(json(id(), "a", "REFUND", "5", "2026-05-15T14:02:11Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CREDIT/DEBIT")));
    }

    @Test
    void badTimestampAndBrokenJsonAreRejected() throws Exception {
        mvc.perform(submit(json(id(), "a", "CREDIT", "5", "yesterday"))).andExpect(status().isBadRequest());
        mvc.perform(submit("{not json")).andExpect(status().isBadRequest());
    }

    @Test
    void oversizedMetadataIsA400NotA500() throws Exception {
        String big = "x".repeat(5000);
        mvc.perform(submit("""
                {"eventId":"%s","accountId":"a","type":"CREDIT","amount":1,"currency":"USD",
                 "eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"note":"%s"}}""".formatted(id(), big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("metadata")));
        verifyNoInteractions(accountClient);
    }

    @Test
    void identicalRequestsRacingEachOther_createExactlyOneEvent() throws Exception {
        accountAccepts();
        String e = id(), a = id();
        String body = json(e, a, "CREDIT", "10", "2026-05-15T14:02:11Z");
        int n = 20;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(submit(body)).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0, ok = 0;
        for (var f : futures) {
            int s = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (s == 201) created++;
            else if (s == 200) ok++;
        }
        pool.shutdown();
        org.assertj.core.api.Assertions.assertThat(created).as("exactly one request creates the event").isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(ok).as("all others are idempotent replays").isEqualTo(n - 1);
        mvc.perform(get("/events").param("account", a)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("APPLIED"));
    }

    // ---- degraded behaviour (service level; the circuit breaker is covered in ResilienceTest) ----

    @Test
    void accountDownGives503_eventStaysPending_readsStillWork_andRetryCompletesIt() throws Exception {
        String e = id(), a = id();
        String body = json(e, a, "CREDIT", "20", "2026-05-15T14:02:11Z");
        when(accountClient.applyTransaction(any(), any()))
                .thenThrow(new AccountServiceException("down", null));

        mvc.perform(submit(body)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", notNullValue()))
                .andExpect(jsonPath("$.message", containsString("PENDING")));

        // the Gateway's own data is still readable
        mvc.perform(get("/events/{id}", e)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/events").param("account", a)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Account Service recovers; the client retries the SAME eventId -> not rejected as a duplicate
        reset(accountClient);
        accountAccepts();
        mvc.perform(submit(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        verify(accountClient, times(1)).applyTransaction(any(), any());
    }

    @Test
    void accountRejectionIsSurfacedAndRecordedAsRejected() throws Exception {
        String e = id(), a = id();
        when(accountClient.applyTransaction(any(), any()))
                .thenThrow(new AccountRejectedException(422, "Account holds EUR"));
        mvc.perform(submit(json(e, a, "CREDIT", "20", "2026-05-15T14:02:11Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Account holds EUR"));
        mvc.perform(get("/events/{id}", e)).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureReason").value("Account holds EUR"));
    }

    @Test
    void balanceWhileAccountDownIsA503WithClearMessage() throws Exception {
        when(accountClient.getBalance(any())).thenThrow(new AccountServiceException("down", null));
        mvc.perform(get("/accounts/{a}/balance", "x")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("unreachable")));
    }

    @Test
    void balanceIsPassedThrough() throws Exception {
        when(accountClient.getBalance("acct-9")).thenReturn(new AccountBalance("acct-9", new BigDecimal("12.5"), "USD"));
        mvc.perform(get("/accounts/{a}/balance", "acct-9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(12.5));
    }

    @Test
    void healthReportsDatabaseAndCircuitBreaker() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("event-gateway"))
                .andExpect(jsonPath("$.checks.database.status").value("UP"))
                .andExpect(jsonPath("$.checks.accountService.circuitBreaker").exists());
    }
}
