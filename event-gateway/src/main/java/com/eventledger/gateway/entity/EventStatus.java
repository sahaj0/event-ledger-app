package com.eventledger.gateway.entity;

/**
 * PENDING  - stored, but the Account Service has not (yet) confirmed it. A client retry re-drives it.
 * APPLIED  - the Account Service confirmed the transaction.
 * REJECTED - the Account Service permanently refused it (4xx, e.g. currency mismatch); never retried.
 */
public enum EventStatus {
    PENDING,
    APPLIED,
    REJECTED
}
