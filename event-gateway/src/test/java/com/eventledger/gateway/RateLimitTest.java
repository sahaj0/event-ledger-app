package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountClient;
import com.eventledger.gateway.client.AccountTransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tight limit (3 per hour per client) so the 4th submission is refused. Own Spring context. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@TestPropertySource(properties = {
        "gateway.rate-limit.limit-for-period=3",
        "gateway.rate-limit.refresh-period=1h"})
class RateLimitTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountClient accountClient;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit() {
        return post("/events").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"%s","accountId":"rl","type":"CREDIT","amount":1,"currency":"USD",
                 "eventTimestamp":"2026-05-15T09:00:00Z"}""".formatted(UUID.randomUUID()));
    }

    @Test
    void submissionsOverTheLimitGet429WithRetryAfter_andReadsAreNotLimited() throws Exception {
        when(accountClient.applyTransaction(any(), any()))
                .thenReturn(new AccountTransactionResponse(BigDecimal.ONE, false));

        for (int i = 0; i < 3; i++) {
            mvc.perform(submit()).andExpect(status().isCreated());
        }
        mvc.perform(submit())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", notNullValue()))
                .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message", containsString("Rate limit")));

        // only POST /events is limited
        mvc.perform(get("/events").param("account", "rl")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }
}
