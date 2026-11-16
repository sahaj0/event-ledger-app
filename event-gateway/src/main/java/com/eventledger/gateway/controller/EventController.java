package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountBalance;
import com.eventledger.gateway.dto.request.EventRequest;
import com.eventledger.gateway.dto.response.EventResponse;
import com.eventledger.gateway.dto.response.SubmitResult;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    /** 201 Created for a new event; 200 OK with the original event for a replay of the same eventId. */
    @PostMapping("/events")
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        SubmitResult result = service.submit(request);
        if (result.created()) {
            var location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/events/{id}")
                    .buildAndExpand(result.event().eventId()).toUri();
            return ResponseEntity.created(location).body(result.event());
        }
        return ResponseEntity.status(HttpStatus.OK).body(result.event());
    }

    @GetMapping("/events/{id}")
    public EventResponse get(@PathVariable String id) {
        return service.getByEventId(id);
    }

    private static final int MAX_PAGE_SIZE = 500;

    /**
     * Chronological by eventTimestamp. Optional paging: {@code page} (0-based, default 0) and {@code size}
     * (default 100, max 500). The account's total number of events is returned in the {@code X-Total-Count} header.
     */
    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> list(@RequestParam("account") String accountId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "100") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new com.eventledger.gateway.exception.InvalidRequestException(
                    "page must be >= 0 and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        var result = service.listForAccount(accountId, page, size);
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(result.total())).body(result.items());
    }

    /** Pass-through to the Account Service: 503 with a clear message when it is unreachable. */
    @GetMapping("/accounts/{accountId}/balance")
    public AccountBalance balance(@PathVariable String accountId) {
        return service.getBalance(accountId);
    }
}
