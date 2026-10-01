package io.github.dmishra2022.jev.supplychain.web;

import io.github.dmishra2022.jev.JevApiException;
import io.github.dmishra2022.jev.JevAuthenticationException;
import io.github.dmishra2022.jev.JevConnectionException;
import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.JevPermissionDeniedException;
import io.github.dmishra2022.jev.JevRateLimitException;
import io.github.dmishra2022.jev.JevServerException;
import io.github.dmishra2022.jev.JevTimeoutException;
import io.github.dmishra2022.jev.supplychain.service.UnknownPurchaseOrderException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem responses. Upstream (Jev) failures become 502/503/504 with the TypeSafe request id for support,
 * never the upstream body or credentials.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnknownPurchaseOrderException.class)
    ProblemDetail unknownPurchaseOrder(UnknownPurchaseOrderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(JevException.class)
    ResponseEntity<ProblemDetail> jev(JevException e) {
        HttpStatus status = switch (e) {
            case JevRateLimitException _ -> HttpStatus.SERVICE_UNAVAILABLE;
            case JevServerException server when server.isOverloaded() -> HttpStatus.SERVICE_UNAVAILABLE;
            case JevTimeoutException _ -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        String detail = switch (e) {
            case JevAuthenticationException _, JevPermissionDeniedException _ ->
                    "The decision model rejected this service's credentials.";
            case JevTimeoutException _ -> "The decision model did not answer in time.";
            case JevConnectionException _ -> "The decision model is unreachable.";
            case JevApiException api when status == HttpStatus.SERVICE_UNAVAILABLE ->
                    "The decision model is busy (HTTP " + api.status() + "); retry later.";
            default -> "The decision model call failed.";
        };
        LOG.warn("Jev call failed: {}", e.toString());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Upstream decision model error");
        var headers = new HttpHeaders();
        if (e instanceof JevApiException api) {
            api.requestId().ifPresent(id -> problem.setProperty("upstreamRequestId", id));
            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                long seconds = Math.max(1, api.retryAfter().map(Duration::toSeconds).orElse(5L));
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
            }
        }
        return ResponseEntity.status(status).headers(headers).body(problem);
    }
}
