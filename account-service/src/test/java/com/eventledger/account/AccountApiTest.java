package com.eventledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountApiTest {

    @Autowired
    MockMvc mvc;

    /** Each test uses its own account id, so tests do not depend on each other (shared H2). */
    private static String newAccount() {
        return "acct-" + UUID.randomUUID();
    }

    private MockHttpServletRequestBuilder apply(String account, String eventId, String type, String amount,
                                                String currency, String ts) {
        return post("/accounts/{a}/transactions", account).contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"%s","type":"%s","amount":%s,"currency":"%s","eventTimestamp":"%s"}
                """.formatted(eventId, type, amount, currency, ts));
    }

    @Test
    void creditsAndDebitsProduceNetBalance() throws Exception {
        String a = newAccount();
        mvc.perform(apply(a, a + "-1", "CREDIT", "150.00", "USD", "2026-05-15T14:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(150.00))
                .andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(apply(a, a + "-2", "DEBIT", "40.50", "USD", "2026-05-15T15:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(109.50));
        mvc.perform(get("/accounts/{a}/balance", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(109.50))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void duplicateEventIdDoesNotChangeBalance() throws Exception {
        String a = newAccount();
        mvc.perform(apply(a, a + "-1", "CREDIT", "100", "USD", "2026-05-15T14:00:00Z")).andExpect(status().isCreated());
        mvc.perform(apply(a, a + "-1", "CREDIT", "100", "USD", "2026-05-15T14:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.balance").value(100.00));
        mvc.perform(get("/accounts/{a}", a))
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void outOfOrderArrivalGivesSameBalanceAndTimestampOrderedHistory() throws Exception {
        String a = newAccount();
        // arrives first but happened last
        mvc.perform(apply(a, a + "-late", "DEBIT", "30", "USD", "2026-05-15T18:00:00Z")).andExpect(status().isCreated());
        mvc.perform(apply(a, a + "-early", "CREDIT", "100", "USD", "2026-05-15T09:00:00Z")).andExpect(status().isCreated());
        mvc.perform(get("/accounts/{a}", a))
                .andExpect(jsonPath("$.balance").value(70.00))
                // recent transactions are newest-first by event timestamp, not by arrival
                .andExpect(jsonPath("$.recentTransactions[0].eventId").value(a + "-late"))
                .andExpect(jsonPath("$.recentTransactions[1].eventId").value(a + "-early"));
    }

    @Test
    void sameEventIdOnDifferentAccountIsAConflict() throws Exception {
        String a = newAccount();
        String b = newAccount();
        mvc.perform(apply(a, a + "-1", "CREDIT", "10", "USD", "2026-05-15T14:00:00Z")).andExpect(status().isCreated());
        mvc.perform(apply(b, a + "-1", "CREDIT", "10", "USD", "2026-05-15T14:00:00Z")).andExpect(status().isConflict());
    }

    @Test
    void currencyMismatchIsRejected() throws Exception {
        String a = newAccount();
        mvc.perform(apply(a, a + "-1", "CREDIT", "10", "USD", "2026-05-15T14:00:00Z")).andExpect(status().isCreated());
        mvc.perform(apply(a, a + "-2", "CREDIT", "10", "EUR", "2026-05-15T14:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("USD")));
    }

    @Test
    void invalidRequestsAreRejectedWithMessages() throws Exception {
        String a = newAccount();
        mvc.perform(apply(a, a + "-1", "CREDIT", "0", "USD", "2026-05-15T14:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("amount")));
        mvc.perform(apply(a, a + "-1", "REFUND", "5", "USD", "2026-05-15T14:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/accounts/{a}/transactions", a).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    void identicalTransactionsRacingEachOther_areAppliedExactlyOnce() throws Exception {
        String a = newAccount();
        int n = 20;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(apply(a, a + "-race", "CREDIT", "10", "USD", "2026-05-15T14:00:00Z"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0, duplicates = 0;
        for (var f : futures) {
            int s = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (s == 201) created++;
            else if (s == 200) duplicates++;
        }
        pool.shutdown();
        org.assertj.core.api.Assertions.assertThat(created).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(duplicates).isEqualTo(n - 1);
        mvc.perform(get("/accounts/{a}", a))
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(jsonPath("$.balance").value(10.00));
    }

    @Test
    void unknownCurrencyCodeIsRejected() throws Exception {
        String a = newAccount();
        mvc.perform(apply(a, a + "-1", "CREDIT", "10", "ZZZ", "2026-05-15T14:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("ISO 4217")));
        mvc.perform(apply(a, a + "-2", "CREDIT", "10", "EUR", "2026-05-15T14:00:00Z")).andExpect(status().isCreated());
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(get("/accounts/{a}/balance", "nope-" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void healthReportsDatabase() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("account-service"))
                .andExpect(jsonPath("$.checks.database.status").value("UP"));
    }

    @Test
    void responsesCarryTraceIdHeader() throws Exception {
        mvc.perform(get("/health")).andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f]{32}")));
    }
}
