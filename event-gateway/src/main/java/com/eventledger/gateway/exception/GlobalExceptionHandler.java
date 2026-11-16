package com.eventledger.gateway.exception;

import com.eventledger.gateway.client.AccountRejectedException;
import com.eventledger.gateway.dto.response.ApiError;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;

    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage()).sorted().toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, details, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST,
                "Malformed request body: invalid JSON, a type other than CREDIT/DEBIT, or a bad "
                        + "timestamp (ISO 8601) / amount format", req, List.of(), null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Required query parameter '" + ex.getParameterName() + "' is missing",
                req, List.of(), null);
    }

    @ExceptionHandler(EventNotFoundException.class)
    ResponseEntity<ApiError> notFound(EventNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, List.of(), null);
    }

    @ExceptionHandler(EventConflictException.class)
    ResponseEntity<ApiError> conflict(EventConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, List.of(), null);
    }

    /** Account Service said 4xx: surface its verdict (e.g. 422 currency mismatch, 404 unknown account). */
    @ExceptionHandler(AccountRejectedException.class)
    ResponseEntity<ApiError> rejected(AccountRejectedException ex, HttpServletRequest req) {
        return build(HttpStatus.valueOf(ex.getStatus()), ex.getMessage(), req, List.of(), null);
    }

    /** Account Service down / slow / circuit open. 503 + Retry-After, never a hang and never a 500. */
    @ExceptionHandler(AccountServiceUnavailableException.class)
    ResponseEntity<ApiError> unavailable(AccountServiceUnavailableException ex, HttpServletRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "15");
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req, List.of(), headers);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) throws Exception {
        if (ex instanceof ErrorResponse) {
            throw ex; // Spring's own 4xx (405, 415, ...)
        }
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", req, List.of(), null);
    }

    private ResponseEntity<ApiError> build(HttpStatusCode status, String message, HttpServletRequest req,
                                           List<String> details, HttpHeaders headers) {
        var span = tracer.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        String reason = status instanceof HttpStatus s ? s.getReasonPhrase() : status.toString();
        var builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.body(new ApiError(Instant.now(), status.value(), reason, message, req.getRequestURI(),
                traceId, details));
    }
}
