package com.eventledger.gateway.service.impl;

import com.eventledger.gateway.client.*;
import com.eventledger.gateway.dto.request.EventRequest;
import com.eventledger.gateway.dto.response.EventResponse;
import com.eventledger.gateway.dto.response.SubmitResult;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.exception.EventConflictException;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.service.EventService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Flow of POST /events:
 * <ol>
 *   <li>look the eventId up; a replay never creates a second row;</li>
 *   <li>otherwise persist it as PENDING <em>first</em> (so an accepted event is never lost);</li>
 *   <li>call the Account Service; on success mark APPLIED, on a definitive 4xx mark REJECTED,
 *       on an outage leave PENDING and answer 503 - a client retry of the same eventId re-drives it.</li>
 * </ol>
 * No database transaction is held open across the remote call (the service is not @Transactional;
 * each repository call is its own short transaction), so a slow Account Service cannot pin
 * database connections.
 */
@Service
public class EventServiceImpl implements EventService {

    private static final Logger log = LoggerFactory.getLogger(EventServiceImpl.class);

    private final EventRepository repository;
    private final AccountClient accountClient;
    private final MeterRegistry meters;

    public EventServiceImpl(EventRepository repository, AccountClient accountClient, MeterRegistry meters) {
        this.repository = repository;
        this.accountClient = accountClient;
        this.meters = meters;
    }

    @Override
    public SubmitResult submit(EventRequest req) {
        var existing = repository.findByEventId(req.eventId());
        if (existing.isPresent()) {
            return replay(existing.get(), req);
        }

        Event event = new Event(req.eventId(), req.accountId(), req.type(), req.amount(), req.currency(),
                req.eventTimestamp(), req.metadata());
        try {
            event = repository.saveAndFlush(event);
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same eventId inserted first: we are the duplicate.
            Event winner = repository.findByEventId(req.eventId()).orElseThrow(() -> race);
            return replay(winner, req);
        }
        log.info("Stored event {} ({} {} {}) as PENDING", event.getEventId(), event.getType(),
                event.getAmount(), event.getCurrency());
        return new SubmitResult(apply(event), true);
    }

    private SubmitResult replay(Event existing, EventRequest req) {
        if (!existing.sameContentAs(req.accountId(), req.type(), req.amount(), req.currency(),
                req.eventTimestamp())) {
            count("conflict");
            throw new EventConflictException(req.eventId());
        }
        if (existing.getStatus() == EventStatus.PENDING) {
            // The earlier attempt never reached the ledger: finish the job now (Account Service is idempotent).
            log.info("Duplicate eventId={} is still PENDING; re-driving it to the Account Service", existing.getEventId());
            return new SubmitResult(apply(existing), false);
        }
        count("duplicate");
        log.info("Duplicate eventId={} (status {}); returning the original, nothing re-applied",
                existing.getEventId(), existing.getStatus());
        return new SubmitResult(EventResponse.from(existing), false);
    }

    private EventResponse apply(Event event) {
        try {
            accountClient.applyTransaction(event.getAccountId(), new AccountTransactionRequest(
                    event.getEventId(), event.getType().name(), event.getAmount(), event.getCurrency(),
                    event.getEventTimestamp()));
        } catch (AccountRejectedException rejected) {
            event.markRejected(rejected.getMessage());
            repository.save(event);
            count("rejected");
            log.warn("Account Service rejected event {}: {}", event.getEventId(), rejected.getMessage());
            throw rejected;
        } catch (AccountServiceException | CallNotPermittedException down) {
            count("unavailable");
            log.warn("Event {} left PENDING: Account Service unavailable ({})", event.getEventId(),
                    down.getClass().getSimpleName());
            throw new AccountServiceUnavailableException("Account Service is unavailable. Event '"
                    + event.getEventId() + "' was stored as PENDING; retry the same eventId later.", down);
        }
        event.markApplied();
        repository.save(event);
        count("applied");
        log.info("Event {} APPLIED", event.getEventId());
        return EventResponse.from(event);
    }

    @Override
    public EventResponse getByEventId(String eventId) {
        return repository.findByEventId(eventId).map(EventResponse::from)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @Override
    public List<EventResponse> listForAccount(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAscIdAsc(accountId).stream()
                .map(EventResponse::from).toList();
    }

    @Override
    public AccountBalance getBalance(String accountId) {
        try {
            return accountClient.getBalance(accountId);
        } catch (AccountServiceException | CallNotPermittedException down) {
            throw new AccountServiceUnavailableException(
                    "Account Service is unreachable; the balance cannot be determined right now.", down);
        }
    }

    private void count(String outcome) {
        meters.counter("gateway.events.submitted", "outcome", outcome).increment();
    }
}
