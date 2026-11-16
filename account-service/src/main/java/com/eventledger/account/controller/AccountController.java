package com.eventledger.account.controller;

import com.eventledger.account.dto.request.ApplyTransactionRequest;
import com.eventledger.account.dto.response.AccountDetailsResponse;
import com.eventledger.account.dto.response.ApplyTransactionResponse;
import com.eventledger.account.dto.response.BalanceResponse;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts/{accountId}")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    /** 201 when newly applied, 200 when the eventId had already been applied (idempotent replay). */
    @PostMapping("/transactions")
    public ResponseEntity<ApplyTransactionResponse> apply(@PathVariable String accountId,
                                                          @Valid @RequestBody ApplyTransactionRequest request) {
        var result = service.applyTransaction(accountId, request);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return service.getBalance(accountId);
    }

    @GetMapping
    public AccountDetailsResponse details(@PathVariable String accountId) {
        return service.getAccount(accountId);
    }
}
