package com.eventledger.account.service.impl;

import com.eventledger.account.dto.request.ApplyTransactionRequest;
import com.eventledger.account.dto.response.AccountDetailsResponse;
import com.eventledger.account.dto.response.ApplyTransactionResponse;
import com.eventledger.account.dto.response.BalanceResponse;
import com.eventledger.account.dto.response.TransactionResponse;
import com.eventledger.account.entity.LedgerTransaction;
import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.exception.CurrencyMismatchException;
import com.eventledger.account.exception.EventConflictException;
import com.eventledger.account.repository.LedgerTransactionRepository;
import com.eventledger.account.service.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Deliberately NOT @Transactional at the method level: each repository call is its own short
 * transaction, so when the UNIQUE(event_id) constraint rejects a racing duplicate we can still
 * catch the exception and answer with the original row (a failed flush inside one outer
 * transaction would mark it rollback-only).
 */
@Service
public class AccountServiceImpl implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);

    private final LedgerTransactionRepository repository;
    private final MeterRegistry meters;

    public AccountServiceImpl(LedgerTransactionRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
    }

    @Override
    public ApplyTransactionResponse applyTransaction(String accountId, ApplyTransactionRequest req) {
        var existing = repository.findByEventId(req.eventId());
        if (existing.isPresent()) {
            return duplicate(accountId, existing.get());
        }

        // All transactions of an account share one currency (the first one wins); summing
        // mixed currencies into a single balance would be meaningless.
        repository.findFirstByAccountIdOrderByIdAsc(accountId).ifPresent(first -> {
            if (!first.getCurrency().equals(req.currency())) {
                count(req, "rejected");
                throw new CurrencyMismatchException(accountId, first.getCurrency(), req.currency());
            }
        });

        try {
            var saved = repository.saveAndFlush(new LedgerTransaction(req.eventId(), accountId, req.type(),
                    req.amount(), req.currency(), req.eventTimestamp()));
            count(req, "applied");
            log.info("Applied {} of {} {} to account {} (eventId={}, eventTimestamp={})",
                    req.type(), req.amount(), req.currency(), accountId, req.eventId(), req.eventTimestamp());
            return ApplyTransactionResponse.of(TransactionResponse.from(saved),
                    repository.computeBalance(accountId), false);
        } catch (DataIntegrityViolationException race) {
            // Another request with the same eventId won the race; behave like the duplicate it is.
            var winner = repository.findByEventId(req.eventId()).orElseThrow(() -> race);
            return duplicate(accountId, winner);
        }
    }

    private ApplyTransactionResponse duplicate(String accountId, LedgerTransaction existing) {
        if (!existing.getAccountId().equals(accountId)) {
            throw new EventConflictException(existing.getEventId(), existing.getAccountId());
        }
        meters.counter("account.transactions", "type", existing.getType().name(), "outcome", "duplicate")
                .increment();
        log.info("Duplicate eventId={} for account {} ignored; balance unchanged", existing.getEventId(), accountId);
        return ApplyTransactionResponse.of(TransactionResponse.from(existing),
                repository.computeBalance(accountId), true);
    }

    @Override
    public BalanceResponse getBalance(String accountId) {
        var first = requireAccount(accountId);
        return BalanceResponse.of(accountId, repository.computeBalance(accountId), first.getCurrency());
    }

    @Override
    public AccountDetailsResponse getAccount(String accountId) {
        var first = requireAccount(accountId);
        BigDecimal balance = repository.computeBalance(accountId);
        List<TransactionResponse> recent = repository
                .findTop10ByAccountIdOrderByEventTimestampDescIdDesc(accountId).stream()
                .map(TransactionResponse::from).toList();
        return AccountDetailsResponse.of(accountId, first.getCurrency(), balance,
                repository.countByAccountId(accountId), recent);
    }

    private LedgerTransaction requireAccount(String accountId) {
        return repository.findFirstByAccountIdOrderByIdAsc(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void count(ApplyTransactionRequest req, String outcome) {
        meters.counter("account.transactions", "type", req.type().name(), "outcome", outcome).increment();
    }
}
