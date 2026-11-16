package com.eventledger.gateway.repository;

import com.eventledger.gateway.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByEventId(String eventId);

    /** Oldest-first page of events still waiting for the Account Service (used by the automatic re-drive). */
    java.util.List<Event> findByStatusAndReceivedAtBeforeOrderByReceivedAtAsc(
            com.eventledger.gateway.entity.EventStatus status, java.time.Instant before,
            org.springframework.data.domain.Pageable page);

    /** One page of an account's events; the caller supplies the chronological sort. */
    org.springframework.data.domain.Page<Event> findByAccountId(String accountId,
                                                                org.springframework.data.domain.Pageable pageable);

    /** Chronological by when the event happened (not by arrival); id breaks ties deterministically. */
    List<Event> findByAccountIdOrderByEventTimestampAscIdAsc(String accountId);
}
