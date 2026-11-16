package com.eventledger.gateway.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The only class that talks to the Account Service. Resilience is declared here, once:
 * {@code @Retry} (outer) wraps {@code @CircuitBreaker} (inner), which wraps the HTTP call that
 * has connect/read timeouts (see AccountClientConfig).
 *
 * <p>Retrying the POST is safe because the Account Service is idempotent on eventId.
 *
 * <p>Methods must be called from another bean so the AOP proxy applies; no fallback is declared
 * on purpose - callers translate failures into a 503 (see EventServiceImpl).
 */
@Component
public class AccountClient {

    private static final Logger log = LoggerFactory.getLogger(AccountClient.class);

    private final RestClient restClient;
    private final MeterRegistry meters;

    public AccountClient(RestClient accountRestClient, MeterRegistry meters) {
        this.restClient = accountRestClient;
        this.meters = meters;
    }

    @Retry(name = "account")
    @CircuitBreaker(name = "account")
    public AccountTransactionResponse applyTransaction(String accountId, AccountTransactionRequest body) {
        return timed("apply-transaction", () -> restClient.post()
                .uri("/accounts/{accountId}/transactions", accountId)
                .body(body)
                .retrieve()
                .body(AccountTransactionResponse.class));
    }

    @Retry(name = "account")
    @CircuitBreaker(name = "account")
    public AccountBalance getBalance(String accountId) {
        return timed("get-balance", () -> restClient.get()
                .uri("/accounts/{accountId}/balance", accountId)
                .retrieve()
                .body(AccountBalance.class));
    }

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                outcome = "rejected";
                throw new AccountRejectedException(e.getStatusCode().value(), messageOf(e));
            }
            outcome = "error";
            log.warn("Account Service {} failed with HTTP {}", operation, e.getStatusCode().value());
            throw new AccountServiceException("Account Service returned HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            outcome = "error";
            log.warn("Account Service {} unreachable or timed out: {}", operation, e.getMessage());
            throw new AccountServiceException("Account Service unreachable: " + e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("gateway.account.call").tag("operation", operation)
                    .tag("outcome", outcome).register(meters));
        }
    }

    @SuppressWarnings("unchecked")
    private static String messageOf(RestClientResponseException e) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("message") != null) {
                return body.get("message").toString();
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic message
        }
        return "Account Service rejected the request (HTTP " + e.getStatusCode().value() + ")";
    }
}
