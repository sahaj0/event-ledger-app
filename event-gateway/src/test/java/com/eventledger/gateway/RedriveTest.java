package com.eventledger.gateway;

import com.eventledger.gateway.client.*;
import com.eventledger.gateway.dto.request.EventRequest;
import com.eventledger.gateway.entity.EventType;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scheduler itself is switched off here; we call the re-drive directly (with min-age 0) so the test is
 * deterministic. Events from other test classes may also be PENDING in the shared in-memory DB, so we only
 * assert on our own events.
 */
@SpringBootTest
@TestPropertySource(properties = "gateway.redrive.enabled=false")
class RedriveTest {

    @Autowired
    EventService service;

    @MockitoBean
    AccountClient accountClient;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private EventRequest event(String eventId, String account) {
        return new EventRequest(eventId, account, EventType.CREDIT, BigDecimal.TEN, "USD",
                Instant.parse("2026-05-15T14:02:11Z"), null);
    }

    private void submitWhileAccountDown(String eventId, String account) {
        when(accountClient.applyTransaction(eq(account), any())).thenThrow(new AccountServiceException("down", null));
        assertThatThrownBy(() -> service.submit(event(eventId, account)))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void pendingEventsAreAppliedAutomaticallyOnceAccountServiceRecovers() {
        String a = id(), e1 = id(), e2 = id();
        submitWhileAccountDown(e1, a);
        submitWhileAccountDown(e2, a);
        assertThat(service.getByEventId(e1).status().name()).isEqualTo("PENDING");

        reset(accountClient);                                   // Account Service is back
        when(accountClient.applyTransaction(any(), any())).thenReturn(new AccountTransactionResponse(BigDecimal.TEN, false));
        int applied = service.redrivePending(Duration.ZERO, 1000);

        assertThat(applied).isGreaterThanOrEqualTo(2);
        assertThat(service.getByEventId(e1).status().name()).isEqualTo("APPLIED");
        assertThat(service.getByEventId(e2).status().name()).isEqualTo("APPLIED");
        assertThat(service.listForAccount(a)).hasSize(2);       // no duplicate rows
    }

    @Test
    void redriveStopsAtFirstOutageInsteadOfHammeringTheAccountService() {
        String a = id(), e = id();
        submitWhileAccountDown(e, a);

        reset(accountClient);
        when(accountClient.applyTransaction(any(), any())).thenThrow(new AccountServiceException("still down", null));
        int applied = service.redrivePending(Duration.ZERO, 1000);

        assertThat(applied).isZero();
        verify(accountClient, times(1)).applyTransaction(any(), any());   // gave up after the first failure
        assertThat(service.getByEventId(e).status().name()).isEqualTo("PENDING");
    }

    @Test
    void aRejectedEventBecomesRejectedAndDoesNotBlockTheOthers() {
        String a = id(), bad = id(), good = id();
        submitWhileAccountDown(bad, a);
        submitWhileAccountDown(good, a);

        reset(accountClient);
        when(accountClient.applyTransaction(eq(a), argThat(r -> r.eventId().equals(bad))))
                .thenThrow(new AccountRejectedException(422, "Account holds EUR"));
        when(accountClient.applyTransaction(eq(a), argThat(r -> r.eventId().equals(good))))
                .thenReturn(new AccountTransactionResponse(BigDecimal.TEN, false));
        service.redrivePending(Duration.ZERO, 1000);

        assertThat(service.getByEventId(bad).status().name()).isEqualTo("REJECTED");
        assertThat(service.getByEventId(good).status().name()).isEqualTo("APPLIED");
    }

    @Test
    void freshPendingEventsAreLeftToTheClientsOwnRetry() {
        String a = id(), e = id();
        submitWhileAccountDown(e, a);

        reset(accountClient);
        when(accountClient.applyTransaction(any(), any())).thenReturn(new AccountTransactionResponse(BigDecimal.TEN, false));
        service.redrivePending(Duration.ofMinutes(5), 1000);             // min-age far larger than the event's age

        assertThat(service.getByEventId(e).status().name()).isEqualTo("PENDING");
        verify(accountClient, never()).applyTransaction(eq(a), any());
    }
}
