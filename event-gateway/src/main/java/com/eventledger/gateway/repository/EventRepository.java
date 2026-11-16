package com.eventledger.gateway.repository;

import com.eventledger.gateway.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByEventId(String eventId);

    /** Chronological by when the event happened (not by arrival); id breaks ties deterministically. */
    List<Event> findByAccountIdOrderByEventTimestampAscIdAsc(String accountId);
}
