package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;

/** The request body failed server validation (422); {@link #body()} details the offending field. */
public class JevUnprocessableEntityException extends JevApiException {

    private static final long serialVersionUID = 1L;

    public JevUnprocessableEntityException(String message, int status, String body, HttpHeaders headers, String endpoint, Duration retryAfter) {
        super(message, status, body, headers, endpoint, retryAfter);
    }
}
