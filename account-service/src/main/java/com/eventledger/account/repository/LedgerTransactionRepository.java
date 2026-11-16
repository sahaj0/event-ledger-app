package com.eventledger.account.repository;

import com.eventledger.account.entity.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByEventId(String eventId);

    long countByAccountId(String accountId);

    Optional<LedgerTransaction> findFirstByAccountIdOrderByIdAsc(String accountId);

    List<LedgerTransaction> findTop10ByAccountIdOrderByEventTimestampDescIdDesc(String accountId);

    /** Net balance = sum(CREDIT) - sum(DEBIT), computed by the database. */
    @Query("""
            select coalesce(sum(case when t.type = com.eventledger.account.entity.TransactionType.CREDIT
                                     then t.amount else -t.amount end), 0)
            from LedgerTransaction t where t.accountId = :accountId
            """)
    BigDecimal computeBalance(@Param("accountId") String accountId);
}
