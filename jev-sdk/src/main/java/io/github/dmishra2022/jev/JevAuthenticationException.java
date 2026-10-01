package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;

/** Missing or invalid API key (401). */
public class JevAuthenticationException extends JevApiException {

    private static final long serialVersionUID = 1L;

    public JevAuthenticationException(String message, int status, String body, HttpHeaders headers, String endpoint, Duration retryAfter) {
        super(message, status, body, headers, endpoint, retryAfter);
    }
}
